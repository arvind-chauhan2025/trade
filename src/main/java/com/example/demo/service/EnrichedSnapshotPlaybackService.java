package com.example.demo.service;

import com.example.demo.config.PaperTradingProperties;
import com.example.demo.dto.TickSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Replays already-persisted {@code enriched_snapshot} rows for a chosen trade date back over the same
 * {@code /ws/tick-snapshot} WebSocket that {@link TickSnapshotBroadcastService} normally feeds live, so a
 * past day's data can be watched again on the dashboard (e.g. to review how divergence/entry/exit signals
 * behaved) without needing the live/mock tick pipeline running. Rows are read once via
 * {@link EnrichedSnapshotPersistenceService#getSnapshots(LocalDate, LocalDate)} (they were already fully
 * enriched when originally persisted, so nothing is recomputed here) and are pushed out one at a time,
 * spaced according to each row's real recorded gap to the next one divided by a caller-supplied speed
 * multiplier (e.g. {@code speed=10} replays 10x faster than the original pace).
 * <p>
 * Optionally (when {@code backtest=true} is passed to {@link #start}), each row is also fed through a
 * self-contained, in-memory reimplementation of {@link PaperTradingService}'s entry/exit rules (same
 * thresholds from {@link PaperTradingProperties}, same order-anchored divergence math reused from
 * {@link PremiumReferenceService#fixedItmCeDivergenceFrom}/{@link PremiumReferenceService#fixedItmPeDivergenceFrom}),
 * recording simulated trades into {@link #lastBacktestTrades()}. This is deliberately kept entirely
 * separate from {@link PaperTradingService}/{@code PaperOrderPersistenceService}: it never opens/closes a
 * real paper trade, never writes to the live order tables, and its streak/position state resets on every
 * {@link #start}, so it can never corrupt or interfere with live trading. One limitation: the entry-anchored
 * Greeks it uses come from each entry row's own {@code fixedItmCe/PeGamma30m}/{@code Theta30m} columns (the
 * closest thing persisted per-tick) rather than the exact live-cached Greeks {@code PaperTradingService}
 * would have captured at that instant, so anchored divergence is a close approximation, not bit-for-bit
 * identical to what really happened.
 * <p>
 * Only one playback (and, if enabled, backtest) can run at a time; starting a new one stops whatever was
 * already in progress.
 */
@Service
public class EnrichedSnapshotPlaybackService {
    private static final Logger log = LoggerFactory.getLogger(EnrichedSnapshotPlaybackService.class);
    /** Row-to-row pacing is clamped to this range so a data gap (e.g. lunch lull) doesn't stall playback
     * for minutes, and a suspiciously-tiny/zero gap still results in a visible, distinct WebSocket message. */
    private static final Duration MIN_STEP_DELAY = Duration.ofMillis(50);
    private static final Duration MAX_STEP_DELAY = Duration.ofSeconds(5);

    private final EnrichedSnapshotPersistenceService enrichedSnapshotPersistenceService;
    private final TickSnapshotWebSocketHandler webSocketHandler;
    private final PremiumReferenceService premiumReferenceService;
    private final PaperTradingProperties paperTradingProperties;
    private final NiftyMarketDirectionService marketDirectionService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "enriched-snapshot-playback");
        thread.setDaemon(true);
        return thread;
    });

    private final Object lock = new Object();
    private List<Map<String, Object>> rows;
    private int nextIndex;
    private double speed;
    private LocalDate playingDate;
    private ScheduledFuture<?> scheduledStep;

    private boolean backtestEnabled;
    private int entryBullishStreak;
    private int entryBearishStreak;
    private int exitStreak;
    private int fastEntryCeStreak;
    private int fastEntryPeStreak;
    private BacktestPosition openBacktestPosition;
    private List<BacktestTrade> completedTrades = List.of();

    public EnrichedSnapshotPlaybackService(EnrichedSnapshotPersistenceService enrichedSnapshotPersistenceService,
                                            TickSnapshotWebSocketHandler webSocketHandler,
                                            PremiumReferenceService premiumReferenceService,
                                            PaperTradingProperties paperTradingProperties,
                                            NiftyMarketDirectionService marketDirectionService) {
        this.enrichedSnapshotPersistenceService = enrichedSnapshotPersistenceService;
        this.webSocketHandler = webSocketHandler;
        this.premiumReferenceService = premiumReferenceService;
        this.paperTradingProperties = paperTradingProperties;
        this.marketDirectionService = marketDirectionService;
    }

    /** Same as {@link #start(LocalDate, double, boolean)} with {@code backtest=false}. */
    public int start(LocalDate date, double speed) {
        return start(date, speed, false);
    }

    /** Starts replaying {@code date}'s persisted enriched snapshots from the beginning, at {@code speed}x
     * the original pace (must be {@code > 0}; {@code 1.0} = original pace). Stops any playback already in
     * progress first. When {@code backtest} is {@code true}, also resets and re-runs the in-memory
     * paper-trading simulation described in the class doc alongside the replay; its results are available
     * via {@link #lastBacktestTrades()} once playback finishes (or at any point mid-playback, for trades
     * closed so far). Returns the number of rows queued for replay (0 if none were persisted for that
     * date, in which case nothing is scheduled). */
    public int start(LocalDate date, double speed, boolean backtest) {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        if (!(speed > 0)) {
            throw new IllegalArgumentException("speed must be > 0");
        }
        synchronized (lock) {
            stopLocked();
            // Reset backtestEnabled/completedTrades unconditionally, before checking whether any rows
            // were found, so a failed/empty start (wrong date, no data persisted yet, ...) can never leave
            // stale trades from a previous, different-date run visible via lastBacktestTrades().
            this.backtestEnabled = backtest;
            resetBacktestStateLocked();
            List<Map<String, Object>> loaded = enrichedSnapshotPersistenceService.getSnapshots(date, date);
            if (loaded.isEmpty()) {
                log.info("No persisted enriched snapshots found for {}; nothing to replay", date);
                return 0;
            }
            this.rows = loaded;
            this.nextIndex = 0;
            this.speed = speed;
            this.playingDate = date;
            log.info("Starting enriched-snapshot playback for {} ({} rows, speed={}x, backtest={})",
                    date, loaded.size(), speed, backtest);
            scheduledStep = executor.schedule(this::step, 0, TimeUnit.MILLISECONDS);
            return loaded.size();
        }
    }

    /** Stops the current playback (if any) immediately; the next {@link #start} begins a fresh replay
     * from the first row. No-op if nothing is currently playing. */
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void stopLocked() {
        if (scheduledStep != null) {
            scheduledStep.cancel(false);
            scheduledStep = null;
        }
        if (backtestEnabled && openBacktestPosition != null) {
            // Playback stopped/finished mid-trade: record what we have so it's still visible in the
            // results, clearly marked as never having actually exited.
            completedTrades.add(openBacktestPosition.toOpenAtEndTrade());
        }
        rows = null;
        nextIndex = 0;
        playingDate = null;
        openBacktestPosition = null;
    }

    private void resetBacktestStateLocked() {
        entryBullishStreak = 0;
        entryBearishStreak = 0;
        exitStreak = 0;
        fastEntryCeStreak = 0;
        fastEntryPeStreak = 0;
        openBacktestPosition = null;
        completedTrades = new ArrayList<>();
    }

    /** {@code true} while a playback is actively scheduled/in-progress. */
    public boolean isRunning() {
        synchronized (lock) {
            return rows != null;
        }
    }

    /** The date currently being replayed, or {@code null} if no playback is running. */
    public LocalDate playingDate() {
        synchronized (lock) {
            return playingDate;
        }
    }

    /** Sends the row at {@code nextIndex}, then schedules the following row after a delay derived from
     * the recorded gap between the two rows' {@code tickDateTime} (scaled by {@link #speed}, clamped to
     * {@link #MIN_STEP_DELAY}/{@link #MAX_STEP_DELAY}). Stops itself once the last row has been sent. */
    private void step() {
        Map<String, Object> row;
        Duration untilNext = null;
        synchronized (lock) {
            if (rows == null || nextIndex >= rows.size()) {
                stopLocked();
                return;
            }
            row = rows.get(nextIndex);
            if (nextIndex + 1 < rows.size()) {
                Object thisTime = row.get("tickDateTime");
                log.info("Playback Processing next data {}/{} for {}: tickDateTime={}", nextIndex + 1, rows.size(), playingDate, thisTime);
                Object nextTime = rows.get(nextIndex + 1).get("tickDateTime");
                if (thisTime instanceof LocalDateTime a && nextTime instanceof LocalDateTime b) {
                    untilNext = clamp(Duration.between(a, b));
                }
            }
            nextIndex++;
            if (backtestEnabled) {
                runBacktestTick(row);
            }
        }

        broadcast(row);

        if (untilNext != null) {
            synchronized (lock) {
                if (rows != null) {
                    long delayMs = Math.max(1, Math.round(untilNext.toMillis() / speed));
                    scheduledStep = executor.schedule(this::step, delayMs, TimeUnit.MILLISECONDS);
                }
            }
        } else {
            synchronized (lock) {
                if (rows != null) {
                    log.info("Finished enriched-snapshot playback for {} ({} rows replayed)", playingDate, nextIndex);
                    stopLocked();
                }
            }
        }
    }

    private static Duration clamp(Duration raw) {
        if (raw.isNegative() || raw.compareTo(MIN_STEP_DELAY) < 0) {
            return MIN_STEP_DELAY;
        }
        if (raw.compareTo(MAX_STEP_DELAY) > 0) {
            return MAX_STEP_DELAY;
        }
        return raw;
    }

    /** Converts one persisted row (snake_case DB columns) back into the same camelCase shape
     * {@link PremiumReferenceService#enrich} originally produced, and pushes it to every open
     * {@code /ws/tick-snapshot} session, same as a live broadcast cycle. No-op (skips serialization) if no
     * client is currently connected. */
    private void broadcast(Map<String, Object> row) {
        if (webSocketHandler.getSessions().isEmpty()) {
            return;
        }
        Map<String, Object> enriched = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if ("tick_date".equals(key) || "tickDateTime".equals(key)) {
                continue;
            }
            enriched.put("tick_time".equals(key) ? "tickTime" : toCamelCase(key), entry.getValue());
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(enriched);
        } catch (Exception ex) {
            log.warn("Failed to serialize replayed enriched snapshot for broadcast", ex);
            return;
        }

        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : webSocketHandler.getSessions().values()) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (Exception ex) {
                log.warn("Failed to send replayed enriched snapshot to WebSocket session {}", session.getId(), ex);
            }
        }
    }

    /** Reverses {@code EnrichedSnapshotPersistenceService.toSnakeCase}: {@code "atm_ce_divergence30m"} ->
     * {@code "atmCeDivergence30m"}. */
    private static String toCamelCase(String snakeCase) {
        String[] parts = snakeCase.split("_");
        StringBuilder result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return result.toString();
    }

    /** {@code true} if the currently (or most recently) started playback had {@code backtest=true}. */
    public boolean isBacktestEnabled() {
        synchronized (lock) {
            return backtestEnabled;
        }
    }

    /** Simulated trades recorded so far by the current/last backtest run (see the class doc), oldest
     * first. A trade still open when playback stopped/finished is included with {@code exitTime}/
     * {@code exitPremium} {@code null} and {@code exitReason="OPEN_AT_END"}. Empty if no backtest has run
     * yet, or the last {@link #start} had {@code backtest=false}. */
    public List<BacktestTrade> lastBacktestTrades() {
        synchronized (lock) {
            return List.copyOf(completedTrades);
        }
    }

    // ---- In-memory paper-trading backtest engine (mirrors PaperTradingService's rules; see class doc) ----

    private void runBacktestTick(Map<String, Object> row) {
        LocalTime tickTime = tickTimeOf(row);
        if (tickTime == null) {
            return;
        }
        Double ceDivergence30m = num(row, "fixed_itm_ce_divergence30m");
        Double peDivergence30m = num(row, "fixed_itm_pe_divergence30m");

        if (openBacktestPosition == null) {
            backtestEntry(row, tickTime, ceDivergence30m, peDivergence30m);
        } else {
            backtestExit(row, tickTime, ceDivergence30m, peDivergence30m);
        }
    }

    private void backtestEntry(Map<String, Object> row, LocalTime tickTime, Double ceDivergence30m, Double peDivergence30m) {
        boolean fastCe = ceDivergence30m != null && ceDivergence30m >= paperTradingProperties.getFastEntryDivergenceThreshold();
        boolean fastPe = peDivergence30m != null && peDivergence30m >= paperTradingProperties.getFastEntryDivergenceThreshold();
        fastEntryCeStreak = fastCe ? fastEntryCeStreak + 1 : 0;
        fastEntryPeStreak = fastPe ? fastEntryPeStreak + 1 : 0;

        if (fastEntryCeStreak >= paperTradingProperties.getFastEntryConfirmationTicks()) {
            tryOpenBacktestPosition("CE", row, tickTime, "FAST");
            return;
        }
        if (fastEntryPeStreak >= paperTradingProperties.getFastEntryConfirmationTicks()) {
            tryOpenBacktestPosition("PE", row, tickTime, "FAST");
            return;
        }

        boolean bullish = ceDivergence30m != null && peDivergence30m != null
                && ceDivergence30m > paperTradingProperties.getCeBullishThreshold() && peDivergence30m < paperTradingProperties.getPeBullishThreshold();
        boolean bearish = ceDivergence30m != null && peDivergence30m != null
                && ceDivergence30m < paperTradingProperties.getCeBearishThreshold() && peDivergence30m > paperTradingProperties.getPeBearishThreshold();

        if (bullish) {
            entryBullishStreak++;
            entryBearishStreak = 0;
        } else if (bearish) {
            entryBearishStreak++;
            entryBullishStreak = 0;
        } else {
            entryBullishStreak = 0;
            entryBearishStreak = 0;
        }

        if (entryBullishStreak >= paperTradingProperties.getConfirmationTicks()) {
            tryOpenBacktestPosition("CE", row, tickTime, "REGULAR");
        } else if (entryBearishStreak >= paperTradingProperties.getConfirmationTicks()) {
            tryOpenBacktestPosition("PE", row, tickTime, "REGULAR");
        }
    }

    /** Mirrors {@code PaperTradingService.tryOpenPosition}: applies the same 20-minute NIFTY
     * market-direction filter (separate gate alongside the divergence conditions already confirmed by
     * {@link #backtestEntry}) so the backtest faithfully reproduces the live entry rules — a CE entry
     * additionally requires {@code BULLISH} swing structure, a PE entry additionally requires
     * {@code BEARISH} swing structure, and the direction is derived from completed
     * {@code nifty_candle_5m} candles up to {@code playingDate} (the date currently being replayed), never
     * the row's own tick data. Logs direction/swing values and whether the entry was allowed or blocked,
     * and resets the triggering streak(s) on a block so the condition must reconfirm. */
    private void tryOpenBacktestPosition(String direction, Map<String, Object> row, LocalTime tickTime, String entryType) {
        if (!paperTradingProperties.isDirectionFilterEnabled()) {
            openBacktestPosition(direction, row, tickTime, entryType, null);
            return;
        }

        NiftyMarketDirectionService.MarketDirectionResult result =
                marketDirectionService.determineDirection(playingDate, paperTradingProperties.getDirectionSwingCandleCount());
        boolean allowed = "CE".equals(direction)
                ? (result.isBullish() || result.isBullishReversal())
                : (result.isBearish() || result.isBearishReversal());

        if (!allowed) {
            log.info("Backtest {} entry BLOCKED by market-direction filter: requiredDirection={} actualDirection={} "
                            + "entryType={} time={} swingHigh={}->{} swingLow={}->{} reason={}",
                    direction, "CE".equals(direction) ? "BULLISH" : "BEARISH", result.direction(), entryType, tickTime,
                    swingValue(result.previousSwingHigh()), swingValue(result.lastSwingHigh()),
                    swingValue(result.previousSwingLow()), swingValue(result.lastSwingLow()), result.reason());
            if ("CE".equals(direction)) {
                entryBullishStreak = 0;
                fastEntryCeStreak = 0;
            } else {
                entryBearishStreak = 0;
                fastEntryPeStreak = 0;
            }
            return;
        }

        log.info("Backtest {} entry ALLOWED by market-direction filter: direction={} entryType={} time={} "
                        + "swingHigh={}->{} swingLow={}->{}",
                direction, result.direction(), entryType, tickTime,
                swingValue(result.previousSwingHigh()), swingValue(result.lastSwingHigh()),
                swingValue(result.previousSwingLow()), swingValue(result.lastSwingLow()));
        openBacktestPosition(direction, row, tickTime, entryType, result.direction().name());
    }

    private static Double swingValue(NiftyMarketDirectionService.SwingPoint point) {
        return point != null ? point.value() : null;
    }

    private void openBacktestPosition(String direction, Map<String, Object> row, LocalTime tickTime, String entryType,
                                       String marketTrend) {
        Double nifty = num(row, "nifty");
        Double entryPremium = "CE".equals(direction) ? num(row, "fixed_itm_ce") : num(row, "fixed_itm_pe");
        if (nifty == null || entryPremium == null) {
            log.debug("Skipping backtest {} entry at {}: row not fully populated yet", direction, tickTime);
            return;
        }
        PremiumReferenceService.FixedItmOrderReference reference = new PremiumReferenceService.FixedItmOrderReference(
                tickTime, nifty,
                orZero(num(row, "fixed_itm_ce")), num(row, "fixed_itm_ce_strike"), num(row, "fixed_itm_ce_delta"),
                num(row, "fixed_itm_ce_gamma30m"), num(row, "fixed_itm_ce_theta30m"),
                orZero(num(row, "fixed_itm_pe")), num(row, "fixed_itm_pe_strike"), num(row, "fixed_itm_pe_delta"),
                num(row, "fixed_itm_pe_gamma30m"), num(row, "fixed_itm_pe_theta30m"));

        openBacktestPosition = new BacktestPosition(direction, entryType, tickTime, nifty, entryPremium, reference, marketTrend);
        entryBullishStreak = 0;
        entryBearishStreak = 0;
        exitStreak = 0;
        fastEntryCeStreak = 0;
        fastEntryPeStreak = 0;
        log.info("Backtest ENTRY: direction={} entryType={} marketTrend={} time={} entryPremium={} nifty={}",
                direction, entryType, marketTrend, tickTime, entryPremium, nifty);
    }

    private void backtestExit(Map<String, Object> row, LocalTime tickTime, Double ceDivergence30m, Double peDivergence30m) {
        BacktestPosition order = openBacktestPosition;
        Double nifty = num(row, "nifty");
        Double actualPremium = "CE".equals(order.direction) ? num(row, "fixed_itm_ce") : num(row, "fixed_itm_pe");
        if (actualPremium != null && actualPremium > order.highestPremium) {
            order.highestPremium = actualPremium;
        }

        // Reuses PremiumReferenceService's exact Taylor-expansion divergence math against this trade's
        // own entry-anchored reference, same as PaperTradingService.handleOpenOrder does live.
        TickSnapshot syntheticSnapshot = new TickSnapshot(tickTime, nifty,
                null, null, null, null, null, null,
                num(row, "fixed_itm_ce"), num(row, "fixed_itm_ce_strike"), num(row, "fixed_itm_ce_delta"),
                num(row, "fixed_itm_pe"), num(row, "fixed_itm_pe_strike"), num(row, "fixed_itm_pe_delta"),
                null);
        Double anchoredCeDivergence = premiumReferenceService.fixedItmCeDivergenceFrom(order.reference, syntheticSnapshot);
        Double anchoredPeDivergence = premiumReferenceService.fixedItmPeDivergenceFrom(order.reference, syntheticSnapshot);

        boolean exitTrigger;
        if ("CE".equals(order.direction)) {
            exitTrigger = anchoredCeDivergence != null && anchoredPeDivergence != null
                    && ceDivergence30m != null && peDivergence30m != null
                    && (ceDivergence30m < paperTradingProperties.getCeExitThreshold() && peDivergence30m > paperTradingProperties.getPeExitThreshold());
        } else {
            exitTrigger = anchoredCeDivergence != null && anchoredPeDivergence != null
                    && ceDivergence30m != null && peDivergence30m != null
                    && (ceDivergence30m > -paperTradingProperties.getCeExitThreshold() && peDivergence30m < -paperTradingProperties.getPeExitThreshold());
        }
        exitStreak = exitTrigger ? exitStreak + 1 : 0;

        Double directionalDivergence = "CE".equals(order.direction) ? anchoredCeDivergence : anchoredPeDivergence;
        boolean trailingExitApplicable = !paperTradingProperties.isDivergenceTrailingExitFastEntryOnly() || "FAST".equals(order.entryType);
        boolean trailingExitTrigger = trailingExitApplicable && updateBacktestTrailingState(order, directionalDivergence);

        if (exitStreak >= paperTradingProperties.getConfirmationTicks() && actualPremium != null && nifty != null) {
            closeBacktestPosition(tickTime, nifty, actualPremium, "DIVERGENCE_REVERSAL");
            exitStreak = 0;
        } else if (trailingExitTrigger && actualPremium != null && nifty != null) {
            closeBacktestPosition(tickTime, nifty, actualPremium, "DIVERGENCE_TRAILING_RETRACEMENT");
        }
    }

    /** Mirrors {@code PaperTradingService.updateDivergenceTrailingState}. */
    private boolean updateBacktestTrailingState(BacktestPosition order, Double currentDivergence) {
        if (currentDivergence == null) {
            return false;
        }
        if (order.peakDivergence == null || currentDivergence > order.peakDivergence) {
            order.peakDivergence = currentDivergence;
            order.trailingFallStreak = 0;
        } else if (order.previousDivergence != null && currentDivergence < order.previousDivergence) {
            order.trailingFallStreak++;
        } else {
            order.trailingFallStreak = 0;
        }
        order.previousDivergence = currentDivergence;

        double retracement = "CE".equals(order.direction)
                ? paperTradingProperties.getCeDivergenceTrailingRetracement()
                : paperTradingProperties.getPeDivergenceTrailingRetracement();

        return order.trailingFallStreak >= paperTradingProperties.getDivergenceTrailingConfirmationCount()
                && (order.peakDivergence - currentDivergence) >= retracement;
    }

    private void closeBacktestPosition(LocalTime exitTime, double exitNifty, double exitPremium, String reason) {
        BacktestPosition order = openBacktestPosition;
        double pnl = exitPremium - order.entryPremium;
        completedTrades.add(new BacktestTrade(order.direction, order.entryTime, order.entryNifty, order.entryPremium,
                order.entryType, order.marketTrend, exitTime, exitNifty, exitPremium, reason, pnl));
        log.info("Backtest EXIT: direction={} entryTime={} exitTime={} exitPremium={} reason={} pnl={}",
                order.direction, order.entryTime, exitTime, exitPremium, reason, pnl);
        openBacktestPosition = null;
    }

    private static Double num(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static double orZero(Double value) {
        return value != null ? value : 0.0;
    }

    private static LocalTime tickTimeOf(Map<String, Object> row) {
        Object dateTime = row.get("tickDateTime");
        if (dateTime instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalTime();
        }
        Object rawTime = row.get("tick_time");
        return rawTime != null ? LocalTime.parse(String.valueOf(rawTime)) : null;
    }

    /** One simulated CE/PE trade recorded by the backtest engine. {@code exitTime}/{@code exitNifty}/
     * {@code exitPremium}/{@code pnl} are {@code null} when {@code exitReason} is {@code "OPEN_AT_END"}
     * (playback stopped/finished while this trade was still open). {@code marketTrend} is the 20-minute
     * NIFTY market-direction filter's verdict ({@code "BULLISH"}/{@code "BEARISH"}) at entry time, or
     * {@code null} when the filter was disabled. */
    public record BacktestTrade(String direction, LocalTime entryTime, double entryNifty, double entryPremium,
                                 String entryType, String marketTrend, LocalTime exitTime, Double exitNifty,
                                 Double exitPremium, String exitReason, Double pnl) {
    }

    /** Mutable in-progress simulated position, mirroring the fields {@code PaperTradingService}'s
     * {@code PaperOrderState} tracks for an open trade (minus anything DB-persistence-specific). */
    private static final class BacktestPosition {
        final String direction;
        final String entryType;
        final LocalTime entryTime;
        final double entryNifty;
        final double entryPremium;
        final PremiumReferenceService.FixedItmOrderReference reference;
        final String marketTrend;
        double highestPremium;
        Double peakDivergence;
        Double previousDivergence;
        int trailingFallStreak;

        BacktestPosition(String direction, String entryType, LocalTime entryTime, double entryNifty, double entryPremium,
                          PremiumReferenceService.FixedItmOrderReference reference, String marketTrend) {
            this.direction = direction;
            this.entryType = entryType;
            this.entryTime = entryTime;
            this.entryNifty = entryNifty;
            this.entryPremium = entryPremium;
            this.reference = reference;
            this.marketTrend = marketTrend;
            this.highestPremium = entryPremium;
        }

        BacktestTrade toOpenAtEndTrade() {
            return new BacktestTrade(direction, entryTime, entryNifty, entryPremium, entryType, marketTrend,
                    null, null, null, "OPEN_AT_END", null);
        }
    }

    @PreDestroy
    void shutdown() {
        stop();
        executor.shutdownNow();
    }
}
