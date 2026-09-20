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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Detects Support/Resistance zones from today's completed 5-minute {@link NiftyCandle}s (see
 * {@link NiftyCandleService}). Whenever a 5-minute candle completes, {@link #recalculate(LocalDate)} is
 * called to:
 * <ol>
 *     <li>load every completed candle for the day so far;</li>
 *     <li>detect swing highs/lows (a candle is a swing if its High/Low is the extreme among itself and
 *     {@link SupportResistanceProperties#getPivotWindow()} candles on each side);</li>
 *     <li>cluster nearby swing highs into Resistance zones and swing lows into Support zones (points
 *     within {@link SupportResistanceProperties#getZoneTolerancePoints()} of each other merge into the
 *     same zone);</li>
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
                    updated_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (trade_date, zone_type, zone_index)
                )
                """);
        // Restart recovery: recalculate from whatever candles are already persisted for today, using the
        // last persisted candle's close as the current spot (best available reference until the next tick).
        List<NiftyCandle> todaysCandles = niftyCandleService.getCandles(LocalDate.now());
        double lastKnownSpot = todaysCandles.isEmpty() ? 0.0 : todaysCandles.get(todaysCandles.size() - 1).close();
        recalculate(LocalDate.now(), lastKnownSpot);
    }

    /** Recalculates S/R zones from all of {@code tradeDate}'s completed 5-minute candles, keeps only the
     * nearest 2 Resistance zones above {@code currentSpot} and nearest 2 Support zones below it (an
     * "active" S/R zone must sit on the correct side of the current price to be treated as such), replaces
     * that day's persisted zones in {@code nifty_sr_zone} with only those active zones, and updates the
     * in-memory current zones. Never touches the {@code nifty_candle_5m} candle history. */
    public synchronized void recalculate(LocalDate tradeDate, double currentSpot) {
        List<NiftyCandle> candles = niftyCandleService.getCandles(tradeDate);
        List<SrZone> allZones = calculateZones(candles);
        List<SrZone> activeZones = selectActiveZones(allZones, currentSpot);
        persistZones(tradeDate, activeZones);
        currentZones = activeZones;
        log.info("Recalculated S/R for {} (spot={}): {} active zone(s) of {} total from {} candle(s)",
                tradeDate, currentSpot, activeZones.size(), allZones.size(), candles.size());
    }

    /** Keeps only Resistance zones whose level is above {@code currentSpot} (nearest 2) and Support zones
     * whose level is below {@code currentSpot} (nearest 2). A zone on the wrong side of spot (e.g. a
     * "resistance" that price has already broken above) is dropped rather than re-labelled. */
    private List<SrZone> selectActiveZones(List<SrZone> zones, double currentSpot) {
        List<SrZone> resistances = zones.stream()
                .filter(zone -> SrZone.RESISTANCE.equals(zone.type()) && zone.level() > currentSpot)
                .sorted(Comparator.comparingDouble(SrZone::level))
                .limit(2)
                .toList();
        List<SrZone> supports = zones.stream()
                .filter(zone -> SrZone.SUPPORT.equals(zone.type()) && zone.level() < currentSpot)
                .sorted(Comparator.comparingDouble(SrZone::level).reversed())
                .limit(2)
                .toList();
        List<SrZone> active = new ArrayList<>(resistances);
        active.addAll(supports);
        return active;
    }

    /** Dynamically computes the active Support/Resistance zones (nearest 2 above/below {@code currentSpot})
     * for an arbitrary set of candles (e.g. a multi-day range requested via the API), without touching
     * persisted state or the in-memory current-day zones. */
    public List<SrZone> calculateZonesFor(List<NiftyCandle> candles, double currentSpot) {
        return selectActiveZones(calculateZones(candles), currentSpot);
    }

    /** Detects swing highs/lows across {@code candles} and clusters them into Support/Resistance zones. */
    List<SrZone> calculateZones(List<NiftyCandle> candles) {
        int window = properties.getPivotWindow();
        List<Double> swingHighs = new ArrayList<>();
        List<Double> swingLows = new ArrayList<>();

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
                swingHighs.add(candidateHigh);
            }
            if (isSwingLow) {
                swingLows.add(candidateLow);
            }
        }

        double tolerance = properties.getZoneTolerancePoints();
        List<SrZone> zones = new ArrayList<>(clusterIntoZones(swingHighs, SrZone.RESISTANCE, tolerance));
        zones.addAll(clusterIntoZones(swingLows, SrZone.SUPPORT, tolerance));
        return zones;
    }

    /** Sorts {@code prices} and greedily merges consecutive values within {@code tolerance} of each other
     * into the same zone, so multiple distinct Support/Resistance zones can co-exist. */
    private List<SrZone> clusterIntoZones(List<Double> prices, String type, double tolerance) {
        if (prices.isEmpty()) {
            return List.of();
        }
        List<Double> sorted = new ArrayList<>(prices);
        Collections.sort(sorted);

        List<SrZone> zones = new ArrayList<>();
        List<Double> cluster = new ArrayList<>();
        cluster.add(sorted.get(0));
        for (int i = 1; i < sorted.size(); i++) {
            double price = sorted.get(i);
            double previous = cluster.get(cluster.size() - 1);
            if (price - previous <= tolerance) {
                cluster.add(price);
            } else {
                zones.add(toZone(cluster, type));
                cluster = new ArrayList<>();
                cluster.add(price);
            }
        }
        zones.add(toZone(cluster, type));
        return zones;
    }

    private SrZone toZone(List<Double> cluster, String type) {
        double low = Collections.min(cluster);
        double high = Collections.max(cluster);
        double level = cluster.stream().mapToDouble(Double::doubleValue).average().orElse(low);
        return new SrZone(type, low, high, level, cluster.size());
    }

    private void persistZones(LocalDate tradeDate, List<SrZone> zones) {
        try {
            jdbcTemplate.update("DELETE FROM nifty_sr_zone WHERE trade_date = ?", tradeDate);
            LocalDateTime now = LocalDateTime.now();
            int index = 0;
            for (SrZone zone : zones) {
                jdbcTemplate.update("""
                        INSERT INTO nifty_sr_zone (trade_date, zone_type, zone_index, low_price, high_price, level_price, touch_count, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        tradeDate, zone.type(), index++, zone.low(), zone.high(), zone.level(), zone.touchCount(), now);
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
