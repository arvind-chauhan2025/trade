package com.example.demo.service;

import com.example.demo.config.SupportResistanceProperties;
import com.example.demo.dto.NiftyCandle;
import com.example.demo.dto.SrZone;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Detects Support/Resistance zones for NIFTY, searching progressively further back in history until
 * enough valid levels are found near the current spot. Whenever a 5-minute candle completes, {@link
 * #recalculate(LocalDate, double)} is called to:
 * <ol>
 *     <li><b>Priority 1 (current session):</b> load every completed candle for the day so far and detect
 *     swing highs/lows (a candle is a swing if its High/Low is the extreme among itself and {@link
 *     SupportResistanceProperties#getPivotWindow()} candles on each side);</li>
 *     <li><b>Priority 2/3 (previous sessions):</b> if the current session doesn't yield at least 2 valid
 *     Support and 2 valid Resistance zones near spot, progressively pull in the previous trading
 *     session's High/Low/Close, then earlier sessions one at a time (up to {@link
 *     SupportResistanceProperties#getMaxPreviousSessions()} sessions back), stopping as soon as both sides
 *     have enough zones or history runs out;</li>
 *     <li>cluster all candidates gathered so far into zones (candidates within {@link
 *     SupportResistanceProperties#getZoneTolerancePoints()} of each other merge into the same zone,
 *     reinforcing confluence between independent sources rather than creating duplicate zones);</li>
 *     <li>rank zones by proximity to spot, touch count, recency, reaction strength and confluence, and
 *     keep the best 2 Resistance zones above spot and 2 Support zones below it;</li>
 *     <li>replace that day's persisted zones in {@code nifty_sr_zone} and update the in-memory current
 *     zones ({@link #getCurrentZones()}).</li>
 * </ol>
 * On application restart, {@link #start()} recalculates from whatever candles are already persisted for
 * today, so the in-memory S/R state is repopulated without waiting for the next candle. NIFTY spot is the
 * only input; no futures/CE/PE/OI/IV/BOS/liquidity-sweep logic is included.
 */
@Service
public class SupportResistanceService {

    private static final Logger log = LoggerFactory.getLogger(SupportResistanceService.class);

    /** Target number of valid zones wanted on each side of spot before the historical search stops. */
    private static final int TARGET_ZONES_PER_SIDE = 2;

    private final NiftyCandleService niftyCandleService;
    private final SupportResistanceProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final NiftySpotPriceService niftySpotPriceService;

    private volatile List<SrZone> currentZones = List.of();

    public SupportResistanceService(NiftyCandleService niftyCandleService, SupportResistanceProperties properties,
                                     DataSource dataSource, NiftySpotPriceService niftySpotPriceService) {

        this.niftyCandleService = niftyCandleService;
        this.properties = properties;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.niftySpotPriceService = niftySpotPriceService;
    }

    @PostConstruct
    void start() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS nifty_sr_zone (
                    trade_date DATE NOT NULL,
                    zone_type VARCHAR(10) NOT NULL,
                    zone_index INTEGER NOT NULL,
                    low_price DOUBLE PRECISION NOT NULL,
                    high_price DOUBLE PRECISION NOT NULL,
                    level_price DOUBLE PRECISION NOT NULL,
                    touch_count INTEGER NOT NULL,
                    source VARCHAR(100),
                    sessions_back INTEGER,
                    reaction_strength DOUBLE PRECISION,
                    updated_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (trade_date, zone_type, zone_index)
                )
                """);
        // Older deployments may already have the table without these columns; add them if missing rather
        // than dropping/recreating (never touch persisted candle history, and don't lose other zone data).
        addColumnIfMissing("source", "VARCHAR(100)");
        addColumnIfMissing("sessions_back", "INTEGER");
        addColumnIfMissing("reaction_strength", "DOUBLE PRECISION");

        // Restart recovery: recalculate from whatever candles are already persisted for today, using the
        // last persisted candle's close as the current spot (best available reference until the next tick).
        List<NiftyCandle> todaysCandles = niftyCandleService.getCandles(LocalDate.now());
        double lastKnownSpot = todaysCandles.isEmpty() ? 0.0 : todaysCandles.get(todaysCandles.size() - 1).close();
        recalculate(LocalDate.now(), lastKnownSpot);
    }

    private void addColumnIfMissing(String column, String ddlType) {
        try {
            jdbcTemplate.execute("ALTER TABLE nifty_sr_zone ADD COLUMN IF NOT EXISTS " + column + " " + ddlType);
        } catch (Exception ex) {
            log.debug("Could not ensure column {} exists on nifty_sr_zone (may already exist)", column, ex);
        }
    }

    /** Recalculates S/R zones for {@code tradeDate} using a progressive historical search: today's
     * candles first, then (only if needed) previous trading sessions' High/Low/Close, one session at a
     * time, up to {@link SupportResistanceProperties#getMaxPreviousSessions()} sessions back. Keeps only
     * the best 2 Resistance zones above {@code currentSpot} and 2 Support zones below it, replaces that
     * day's persisted zones in {@code nifty_sr_zone} with only those active zones, and updates the
     * in-memory current zones. Never touches the {@code nifty_candle_5m} candle history. */
    public synchronized void recalculate(LocalDate tradeDate, double currentSpot) {
        List<NiftyCandle> candles = niftyCandleService.getCandles(tradeDate);
        ProgressiveSearchResult result = searchProgressively(tradeDate, candles, currentSpot);
        persistZones(tradeDate, result.activeZones());
        currentZones = result.activeZones();
        log.info("Recalculated S/R for {} (spot={}): {} active zone(s) from {} candidate(s) across {} " +
                        "prior session(s) searched ({} candle(s) today)",
                tradeDate, currentSpot, result.activeZones().size(), result.totalCandidates(),
                result.sessionsSearched(), candles.size());
    }

    /** Dynamically computes the active Support/Resistance zones (best 2 above/below {@code currentSpot})
     * for an arbitrary set of candles (e.g. a multi-day range requested via the API), without touching
     * persisted state, the in-memory current-day zones, or the previous-session historical search (the
     * caller already decided exactly which candles to consider). */
    public List<SrZone> calculateZonesFor(List<NiftyCandle> candles, double currentSpot) {
        List<Candidate> candidates = buildIntradayCandidates(candles);
        return selectActiveZones(clusterAll(candidates, properties.getZoneTolerancePoints()), currentSpot);
    }

    /** Result of a progressive historical search: the selected active zones plus bookkeeping for logging. */
    private record ProgressiveSearchResult(List<SrZone> activeZones, int totalCandidates, int sessionsSearched) {
    }

    /** A single candidate Support/Resistance price before clustering, carrying enough metadata to rank
     * and merge it: which session it came from ({@code sessionsBack}, 0 = current session), where it came
     * from ({@code source}), and how strongly price reacted after touching it ({@code reactionStrength},
     * in NIFTY points). */
    private record Candidate(double price, String type, String source, int sessionsBack,
                              double reactionStrength, int touches) {
    }

    /** Priority 1: start from today's intraday swing pivots. Priority 2/3: if that alone doesn't give at
     * least {@value #TARGET_ZONES_PER_SIDE} valid zones on each side of spot, progressively pull in
     * previous trading sessions' High/Low/Close (most recent first), stopping as soon as both sides have
     * enough zones, the configured max lookback is reached, or no more historical sessions exist. */
    private ProgressiveSearchResult searchProgressively(LocalDate tradeDate, List<NiftyCandle> todaysCandles,
                                                         double currentSpot) {
        double tolerance = properties.getZoneTolerancePoints();
        List<Candidate> candidates = new ArrayList<>(buildIntradayCandidates(todaysCandles));
        List<SrZone> active = selectActiveZones(clusterAll(candidates, tolerance), currentSpot);

        int maxSessions = Math.max(0, properties.getMaxPreviousSessions());
        int sessionsSearched = 0;

        if (!hasSufficientZones(active) && maxSessions > 0) {
            List<LocalDate> previousSessions;
            try {
                previousSessions = niftyCandleService.getPreviousTradingDates(tradeDate, maxSessions);
            } catch (Exception ex) {
                log.warn("Failed to look up previous trading sessions before {}; falling back to current " +
                        "session's zones only", tradeDate, ex);
                previousSessions = List.of();
            }

            for (LocalDate sessionDate : previousSessions) {
                sessionsSearched++;
                List<NiftyCandle> sessionCandles;
                try {
                    sessionCandles = niftyCandleService.getCandles(sessionDate);
                } catch (Exception ex) {
                    log.warn("Failed to load candles for prior session {} ({} session(s) back); skipping",
                            sessionDate, sessionsSearched, ex);
                    continue;
                }
                if (sessionCandles.isEmpty()) {
                    log.debug("No candles persisted for prior session {} ({} session(s) back); skipping",
                            sessionDate, sessionsSearched);
                    continue;
                }
                candidates.addAll(buildPreviousSessionCandidates(sessionCandles, sessionsSearched));
                active = selectActiveZones(clusterAll(candidates, tolerance), currentSpot);
                if (hasSufficientZones(active)) {
                    log.debug("Found sufficient S/R zones for {} after searching {} prior session(s)",
                            tradeDate, sessionsSearched);
                    break;
                }
            }
        }
        return new ProgressiveSearchResult(active, candidates.size(), sessionsSearched);
    }

    /** True once both sides have reached {@value #TARGET_ZONES_PER_SIDE} valid zones; the search never
     * forces a second level into existence on a side that only has one (or zero) credible candidates. */
    private boolean hasSufficientZones(List<SrZone> zones) {
        long resistances = zones.stream().filter(zone -> SrZone.RESISTANCE.equals(zone.type())).count();
        long supports = zones.stream().filter(zone -> SrZone.SUPPORT.equals(zone.type())).count();
        return resistances >= TARGET_ZONES_PER_SIDE && supports >= TARGET_ZONES_PER_SIDE;
    }

    /** Keeps only Resistance zones whose level is above {@code currentSpot} (best {@value
     * #TARGET_ZONES_PER_SIDE} by {@link #rankScore}) and Support zones whose level is below {@code
     * currentSpot} (same limit). A zone on the wrong side of spot (e.g. a "resistance" that price has
     * already broken above) is dropped rather than re-labelled; role reversal only happens implicitly,
     * when a broken level later clusters with fresh candidates on its new side. */
    private List<SrZone> selectActiveZones(List<SrZone> zones, double currentSpot) {
        List<SrZone> resistances = zones.stream()
                .filter(zone -> SrZone.RESISTANCE.equals(zone.type()) && zone.level() > currentSpot)
                .sorted(Comparator.comparingDouble((SrZone zone) -> rankScore(zone, currentSpot)).reversed())
                .limit(TARGET_ZONES_PER_SIDE)
                .toList();
        List<SrZone> supports = zones.stream()
                .filter(zone -> SrZone.SUPPORT.equals(zone.type()) && zone.level() < currentSpot)
                .sorted(Comparator.comparingDouble((SrZone zone) -> rankScore(zone, currentSpot)).reversed())
                .limit(TARGET_ZONES_PER_SIDE)
                .toList();
        List<SrZone> active = new ArrayList<>(resistances);
        active.addAll(supports);
        return active;
    }

    /** Composite ranking score, weighted in the requested priority order: proximity to spot dominates
     * (so the search prefers relevant nearby levels over every historical high/low), then touch count,
     * then recency of the latest touch, then the strength of the price reaction after a touch, then a
     * bonus for confluence between independent sources (e.g. an intraday pivot overlapping a previous
     * session's PH/PL in the same zone). All components are normalised to roughly [0, 1] so no single
     * factor can dominate purely due to differences in scale. */
    private double rankScore(SrZone zone, double currentSpot) {
        double distance = Math.abs(zone.level() - currentSpot);
        double proximity = 1.0 / (1.0 + distance / 50.0);
        double touches = Math.min(zone.touchCount(), 5) / 5.0;
        double recency = 1.0 / (1.0 + zone.sessionsBack());
        double reaction = Math.min(zone.reactionStrength(), 100.0) / 100.0;
        double confluence = zone.source().contains("+") ? 1.0 : 0.0;
        return proximity * 0.45 + touches * 0.20 + recency * 0.15 + reaction * 0.12 + confluence * 0.08;
    }

    /** Detects swing highs/lows across {@code candles} (today's completed 5-minute candles), each
     * becoming an {@code INTRADAY_PIVOT} candidate with {@code sessionsBack=0} and a reaction strength
     * measured from the price excursion immediately following the swing. */
    private List<Candidate> buildIntradayCandidates(List<NiftyCandle> candles) {
        int window = properties.getPivotWindow();
        int reactionLookahead = Math.max(window * 2, 4);
        List<Candidate> candidates = new ArrayList<>();

        for (int i = window; i < candles.size() - window; i++) {
            double candidateHigh = candles.get(i).high();
            double candidateLow = candles.get(i).low();
            boolean isSwingHigh = true;
            boolean isSwingLow = true;
            for (int offset = 1; offset <= window; offset++) {
                NiftyCandle before = candles.get(i - offset);
                NiftyCandle after = candles.get(i + offset);
                if (before.high() > candidateHigh || after.high() > candidateHigh) {
                    isSwingHigh = false;
                }
                if (before.low() < candidateLow || after.low() < candidateLow) {
                    isSwingLow = false;
                }
            }
            if (isSwingHigh) {
                double reaction = reactionStrengthAfter(candles, i, reactionLookahead, true);
                candidates.add(new Candidate(candidateHigh, SrZone.RESISTANCE, "INTRADAY_PIVOT", 0, reaction, 1));
            }
            if (isSwingLow) {
                double reaction = reactionStrengthAfter(candles, i, reactionLookahead, false);
                candidates.add(new Candidate(candidateLow, SrZone.SUPPORT, "INTRADAY_PIVOT", 0, reaction, 1));
            }
        }
        return candidates;
    }

    /** Builds candidates for one previous trading session, {@code sessionsBack} sessions before the
     * current one (1 = previous session): the session's High and Low as directional candidates (with
     * reaction strength measured from how far price moved away after the extreme was set), and the
     * session's Close emitted as a weaker, direction-agnostic candidate on both sides (zero reaction
     * strength, since a close isn't a confirmed reversal point the way a High/Low touch is) so it can
     * still contribute to whichever side of spot it ends up relevant for. */
    private List<Candidate> buildPreviousSessionCandidates(List<NiftyCandle> sessionCandles, int sessionsBack) {
        if (sessionCandles.isEmpty()) {
            return List.of();
        }
        int highIndex = 0;
        int lowIndex = 0;
        for (int i = 1; i < sessionCandles.size(); i++) {
            if (sessionCandles.get(i).high() > sessionCandles.get(highIndex).high()) {
                highIndex = i;
            }
            if (sessionCandles.get(i).low() < sessionCandles.get(lowIndex).low()) {
                lowIndex = i;
            }
        }
        double previousHigh = sessionCandles.get(highIndex).high();
        double previousLow = sessionCandles.get(lowIndex).low();
        double previousClose = sessionCandles.get(sessionCandles.size() - 1).close();

        double highReaction = reactionStrengthAfter(sessionCandles, highIndex, sessionCandles.size(), true);
        double lowReaction = reactionStrengthAfter(sessionCandles, lowIndex, sessionCandles.size(), false);

        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate(previousHigh, SrZone.RESISTANCE, "PREV_SESSION_HIGH", sessionsBack, highReaction, 1));
        candidates.add(new Candidate(previousLow, SrZone.SUPPORT, "PREV_SESSION_LOW", sessionsBack, lowReaction, 1));
        candidates.add(new Candidate(previousClose, SrZone.RESISTANCE, "PREV_SESSION_CLOSE", sessionsBack, 0.0, 1));
        candidates.add(new Candidate(previousClose, SrZone.SUPPORT, "PREV_SESSION_CLOSE", sessionsBack, 0.0, 1));
        return candidates;
    }

    /** Measures how far price moved away from a pivot/extreme immediately after it was set: for a
     * high/resistance candidate, how far the subsequent low(s) fell below it; for a low/support
     * candidate, how far the subsequent high(s) rose above it. Used as a proxy for how credible the
     * level's historical reaction was. Returns 0 if there are no candles after the pivot to react. */
    private double reactionStrengthAfter(List<NiftyCandle> candles, int pivotIndex, int lookahead, boolean isHighPivot) {
        int end = Math.min(candles.size(), pivotIndex + 1 + lookahead);
        if (pivotIndex + 1 >= end) {
            return 0.0;
        }
        double pivotPrice = isHighPivot ? candles.get(pivotIndex).high() : candles.get(pivotIndex).low();
        double extreme = isHighPivot ? Double.MAX_VALUE : -Double.MAX_VALUE;
        for (int i = pivotIndex + 1; i < end; i++) {
            extreme = isHighPivot ? Math.min(extreme, candles.get(i).low()) : Math.max(extreme, candles.get(i).high());
        }
        return isHighPivot ? Math.max(0.0, pivotPrice - extreme) : Math.max(0.0, extreme - pivotPrice);
    }

    /** Clusters every gathered {@code candidates} into Resistance and Support zones, merging candidates
     * (regardless of their source/session) within {@code tolerance} of each other into the same zone, so
     * confluent sources reinforce a single zone rather than producing duplicate nearby zones. */
    private List<SrZone> clusterAll(List<Candidate> candidates, double tolerance) {
        List<SrZone> zones = new ArrayList<>(clusterCandidates(candidates, SrZone.RESISTANCE, tolerance));
        zones.addAll(clusterCandidates(candidates, SrZone.SUPPORT, tolerance));
        return zones;
    }

    /** Sorts {@code type}-matching candidates by price and greedily merges consecutive ones within {@code
     * tolerance} of each other into the same zone, so multiple distinct Support/Resistance zones can
     * co-exist. */
    private List<SrZone> clusterCandidates(List<Candidate> candidates, String type, double tolerance) {
        List<Candidate> filtered = candidates.stream()
                .filter(candidate -> type.equals(candidate.type()))
                .sorted(Comparator.comparingDouble(Candidate::price))
                .toList();
        if (filtered.isEmpty()) {
            return List.of();
        }

        List<SrZone> zones = new ArrayList<>();
        List<Candidate> cluster = new ArrayList<>();
        cluster.add(filtered.get(0));
        for (int i = 1; i < filtered.size(); i++) {
            Candidate candidate = filtered.get(i);
            double previous = cluster.get(cluster.size() - 1).price();
            if (candidate.price() - previous <= tolerance) {
                cluster.add(candidate);
            } else {
                zones.add(toZone(cluster, type));
                cluster = new ArrayList<>();
                cluster.add(candidate);
            }
        }
        zones.add(toZone(cluster, type));
        return zones;
    }

    /** Aggregates a cluster of candidates into one zone: touch counts sum (avoiding double-counting is
     * handled upstream, since each pivot/session marker contributes exactly one candidate), the most
     * recent touch (lowest {@code sessionsBack}) and the strongest observed reaction win, and distinct
     * sources are joined so confluence between e.g. an intraday pivot and a previous session's PH/PL is
     * visible rather than lost. */
    private SrZone toZone(List<Candidate> cluster, String type) {
        double low = cluster.stream().mapToDouble(Candidate::price).min().orElseThrow();
        double high = cluster.stream().mapToDouble(Candidate::price).max().orElseThrow();
        double level = cluster.stream().mapToDouble(Candidate::price).average().orElse(low);
        int touchCount = cluster.stream().mapToInt(Candidate::touches).sum();
        int sessionsBack = cluster.stream().mapToInt(Candidate::sessionsBack).min().orElse(0);
        double reactionStrength = cluster.stream().mapToDouble(Candidate::reactionStrength).max().orElse(0.0);
        String source = cluster.stream().map(Candidate::source).distinct().sorted().collect(Collectors.joining("+"));
        return new SrZone(type, low, high, level, touchCount, source, sessionsBack, reactionStrength);
    }

    private void persistZones(LocalDate tradeDate, List<SrZone> zones) {
        try {
            jdbcTemplate.update("DELETE FROM nifty_sr_zone WHERE trade_date = ?", tradeDate);
            LocalDateTime now = LocalDateTime.now();
            int index = 0;
            for (SrZone zone : zones) {
                jdbcTemplate.update("""
                        INSERT INTO nifty_sr_zone (trade_date, zone_type, zone_index, low_price, high_price,
                            level_price, touch_count, source, sessions_back, reaction_strength, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        tradeDate, zone.type(), index++, zone.low(), zone.high(), zone.level(), zone.touchCount(),
                        zone.source(), zone.sessionsBack(), zone.reactionStrength(), now);
            }
        } catch (Exception ex) {
            log.warn("Failed to persist S/R zones for {}", tradeDate, ex);
        }
    }

    /** Returns an immutable snapshot of the most recently calculated Support/Resistance zones. */
    public List<SrZone> getCurrentZones() {
        return List.copyOf(currentZones);
    }
}
