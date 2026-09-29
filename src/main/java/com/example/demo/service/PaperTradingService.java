package com.example.demo.service;

import com.example.demo.config.PaperTradingProperties;
import com.example.demo.dto.TickSnapshot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;

/**
 * Automatic CE/PE paper-trading engine for backtesting: it never places a real Angel One order, it only
 * records simulated entries/exits so their P&L can be analyzed later.
 * <p>
 * Fed once per 5-second cycle by {@link TickSnapshotBroadcastService} with the same {@link TickSnapshot}
 * and enriched divergence map ({@link PremiumReferenceService#enrich}) already used for the WebSocket
 * broadcast and {@code enriched_snapshot} persistence — no separate polling/calculation loop, and no
 * duplicated divergence math.
 * <ul>
 *     <li><b>Entry</b> — while flat, the existing {@code fixedItmCeDivergence30m}/{@code fixedItmPeDivergence30m}
 *     bullish ({@code >3}/{@code <-2}) or bearish ({@code <-2}/{@code >3}) conditions must hold for
 *     {@link PaperTradingProperties#getConfirmationTicks()} consecutive snapshots before a CE/PE trade is
 *     opened (a single noisy tick never triggers an entry). Duplicate entries are impossible while a
 *     trade is already open.</li>
 *     <li><b>Exit</b> — purely divergence-based. Because the shared 30-minute rolling reference keeps
 *     moving, each trade instead gets its own anchored {@link PremiumReferenceService.FixedItmOrderReference}
 *     captured at entry time; the exit condition re-evaluates the CE/PE divergence against that fixed
 *     anchor (not the shared rolling one) for the trade's confirmation window, so exits reflect genuine
 *     reversal from the trade's own entry point. Exit uses its own, much weaker (closer-to-neutral)
 *     {@link PaperTradingProperties#getCeExitThreshold()}/{@link PaperTradingProperties#getPeExitThreshold()}
 *     thresholds rather than the entry thresholds, so a CE trade exits once anchored CE divergence
 *     drops below (or anchored PE divergence rises above) that threshold — no need to wait for a full
 *     opposite-signal reversal; a PE trade mirrors this with the negated thresholds.</li>
 *     <li><b>Hypothetical SL</b> — analysis only. A trailing stop (percentage below the highest premium
 *     seen since entry) is tracked and recorded ({@code hypotheticalSlHit}/{@code ExitPrice}/{@code ExitTime})
 *     purely for later P&amp;L comparison against the real divergence-based exit. It never changes
 *     position state and never places any order.</li>
 * </ul>
 * Every tick a trade is open, its NIFTY price, FIXED ITM CE/PE premiums, global 30-minute divergence and
 * order-anchored divergence are persisted via {@link OrderSnapshotPersistenceService} for full auditability.
 * <p>
 * {@code TradingApplication}'s 30-minute ATM/ITM rolling re-selection swaps live strikes/subscriptions,
 * which would otherwise invalidate an open trade's entry-anchored reference and its live premium feed
 * mid-trade. To avoid that, {@link #hasOpenPosition()} lets {@code TradingApplication} defer that
 * re-selection entirely while a trade is open, and a {@link PaperTradeClosedEvent} is published the
 * instant a trade closes so any re-selection that was overdue during the trade fires immediately rather
 * than waiting up to a minute for the next scheduled check.
 */
@Service
public class PaperTradingService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingService.class);

    private final PremiumReferenceService premiumReferenceService;
    private final PaperOrderPersistenceService orderPersistenceService;
    private final OrderSnapshotPersistenceService snapshotPersistenceService;
    private final NiftyMarketDirectionService marketDirectionService;
    private final PaperTradingProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    private volatile PaperOrderState openOrder;
    private int entryBullishStreak;
    private int entryBearishStreak;
    private int exitStreak;
    /** Consecutive snapshots {@code fixedItmCeDivergence30m}/{@code fixedItmPeDivergence30m} alone has
     * exceeded {@link PaperTradingProperties#getFastEntryDivergenceThreshold()}, driving the fast-entry
     * path in {@link #handleEntry}. */
    private int fastEntryCeStreak;
    private int fastEntryPeStreak;
    private LocalTime lastProcessedTickTime;

    public PaperTradingService(PremiumReferenceService premiumReferenceService,
                                PaperOrderPersistenceService orderPersistenceService,
                                OrderSnapshotPersistenceService snapshotPersistenceService,
                                NiftyMarketDirectionService marketDirectionService,
                                PaperTradingProperties properties,
                                ApplicationEventPublisher eventPublisher) {
        this.premiumReferenceService = premiumReferenceService;
        this.orderPersistenceService = orderPersistenceService;
        this.snapshotPersistenceService = snapshotPersistenceService;
        this.marketDirectionService = marketDirectionService;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /** Returns {@code true} while a paper trade is currently open. Used by {@code TradingApplication} to
     * defer the 30-minute ATM/ITM rolling re-selection until the trade closes, so an open trade's entry
     * strike/anchored reference (and live premium feed) is never invalidated mid-trade by a strike swap. */
    public boolean hasOpenPosition() {
        return openOrder != null;
    }


    /** Resumes tracking a still-open paper trade across a restart, using its persisted entry-anchored
     * reference so exit-divergence confirmation isn't lost. */
    @PostConstruct
    void restoreOpenOrder() {
        Optional<PaperOrderState> restored = orderPersistenceService.getOpenOrder();
        restored.ifPresent(order -> {
            openOrder = order;
            log.info("Resumed tracking open paper trade id={} direction={} entryTime={} entryPremium={}",
                    order.id, order.direction, order.entryTime, order.entryPremium);
        });
    }

    /** Called once per 5-second broadcast cycle with the same snapshot/enriched map already computed for
     * the WebSocket broadcast + {@code enriched_snapshot} persistence. No-op if
     * {@link PaperTradingProperties#isEnabled()} is {@code false}. Also a no-op if {@code snapshot.tickTime()}
     * is the same as the last processed cycle's — i.e. the live feed didn't produce a new complete tick since
     * the last broadcast cycle and {@code TickPersistenceService#getLatestCompleteSnapshot()} is just
     * returning its previously cached snapshot again. Without this guard, a stalled feed would silently
     * re-count the same stale divergence towards {@link #entryBullishStreak}/{@link #entryBearishStreak}
     * (or {@link #exitStreak}) on every 5-second cycle, letting a real confirmation streak complete far
     * faster than {@link PaperTradingProperties#getConfirmationTicks()} actual distinct ticks intend. */
    public synchronized void onSnapshot(TickSnapshot snapshot, Map<String, Object> enriched) {
        if (!properties.isEnabled() || snapshot == null || snapshot.tickTime() == null) {
            return;
        }
        if (snapshot.tickTime().equals(lastProcessedTickTime)) {
            log.debug("Skipping paper-trading cycle: tickTime={} unchanged since last processed cycle (feed stalled?)",
                    snapshot.tickTime());
            return;
        }
        lastProcessedTickTime = snapshot.tickTime();

        Double ceDivergence30m = asDouble(enriched.get("fixedItmCeDivergence30m"));
        Double peDivergence30m = asDouble(enriched.get("fixedItmPeDivergence30m"));

        if (openOrder == null) {
            handleEntry(snapshot, ceDivergence30m, peDivergence30m);
        } else {
            handleOpenOrder(snapshot, ceDivergence30m, peDivergence30m);
        }
    }

    private void handleEntry(TickSnapshot snapshot, Double ceDivergence30m, Double peDivergence30m) {
        boolean fastCe = ceDivergence30m != null && ceDivergence30m >= properties.getFastEntryDivergenceThreshold();
        boolean fastPe = peDivergence30m != null && peDivergence30m >= properties.getFastEntryDivergenceThreshold();
        fastEntryCeStreak = fastCe ? fastEntryCeStreak + 1 : 0;
        fastEntryPeStreak = fastPe ? fastEntryPeStreak + 1 : 0;

        if (fastEntryCeStreak >= properties.getFastEntryConfirmationTicks()) {
            tryOpenPosition("CE", snapshot, ceDivergence30m, peDivergence30m, "FAST");
            return;
        }
        if (fastEntryPeStreak >= properties.getFastEntryConfirmationTicks()) {
            tryOpenPosition("PE", snapshot, ceDivergence30m, peDivergence30m, "FAST");
            return;
        }

        boolean bullish = ceDivergence30m != null && peDivergence30m != null
                && ceDivergence30m > properties.getCeBullishThreshold() && peDivergence30m < properties.getPeBullishThreshold();
        boolean bearish = ceDivergence30m != null && peDivergence30m != null
                && ceDivergence30m < properties.getCeBearishThreshold() && peDivergence30m > properties.getPeBearishThreshold();

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

        if (entryBullishStreak >= properties.getConfirmationTicks()) {
            tryOpenPosition("CE", snapshot, ceDivergence30m, peDivergence30m, "REGULAR");
        } else if (entryBearishStreak >= properties.getConfirmationTicks()) {
            tryOpenPosition("PE", snapshot, ceDivergence30m, peDivergence30m, "REGULAR");
        }
    }

    /** Applies the NIFTY market-structure context filter (a separate, additional gate alongside the
     * existing divergence-based entry conditions already confirmed by the caller): a CE entry is allowed
     * while {@link NiftyMarketDirectionService} reports {@code BULLISH} structure (Higher-High +
     * Higher-Low) <i>or</i> a bullish {@code REVERSAL_CANDIDATE} (Lower-High + Higher-Low — divergence can
     * flip before a full bullish structure has re-formed), and a PE entry is allowed while it reports
     * {@code BEARISH} structure (Lower-High + Lower-Low) or a bearish {@code REVERSAL_CANDIDATE}
     * (Higher-High + Lower-Low). Plain {@code NEUTRAL} blocks both. When
     * {@link PaperTradingProperties#isDirectionFilterEnabled()} is {@code false} the filter is skipped
     * entirely and the entry proceeds purely on the divergence conditions, as before. Always logs the
     * direction, the swing high/low values it was derived from, and whether the entry was allowed or
     * blocked. A blocked entry resets the streak(s) that triggered it, so the divergence condition must
     * reconfirm from scratch rather than firing again on the very next tick while structure is still
     * unfavorable. */
    private void tryOpenPosition(String direction, TickSnapshot snapshot, Double ceDivergence30m, Double peDivergence30m,
                                  String entryType) {
        if (!properties.isDirectionFilterEnabled()) {
            openPosition(direction, snapshot, ceDivergence30m, peDivergence30m, entryType, null);
            return;
        }

        NiftyMarketDirectionService.MarketDirectionResult result =
                marketDirectionService.determineDirection(LocalDate.now(), properties.getDirectionSwingCandleCount());
        boolean allowed = "CE".equals(direction)
                ? (result.isBullish() || result.isBullishReversal())
                : (result.isBearish() || result.isBearishReversal());

        if (!allowed) {
            log.info("{} entry BLOCKED by market-direction filter: requiredDirection={} actualDirection={} "
                            + "entryType={} swingHigh={}->{} swingLow={}->{} reason={}",
                    direction, "CE".equals(direction) ? "BULLISH" : "BEARISH", result.direction(), entryType,
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

        log.info("{} entry ALLOWED by market-direction filter: direction={} entryType={} swingHigh={}->{} swingLow={}->{}",
                direction, result.direction(), entryType,
                swingValue(result.previousSwingHigh()), swingValue(result.lastSwingHigh()),
                swingValue(result.previousSwingLow()), swingValue(result.lastSwingLow()));
        openPosition(direction, snapshot, ceDivergence30m, peDivergence30m, entryType, result.direction().name());
    }

    private static Double swingValue(NiftyMarketDirectionService.SwingPoint point) {
        return point != null ? point.value() : null;
    }

    private void openPosition(String direction, TickSnapshot snapshot, Double ceDivergence30m, Double peDivergence30m,
                               String entryType, String marketTrend) {
        Double entryPremium = "CE".equals(direction) ? snapshot.fixedItmCe() : snapshot.fixedItmPe();
        Double entryStrike = "CE".equals(direction) ? snapshot.fixedItmCeStrike() : snapshot.fixedItmPeStrike();
        PremiumReferenceService.FixedItmOrderReference reference = premiumReferenceService.captureFixedItmOrderReference(snapshot);
        if (entryPremium == null || snapshot.nifty() == null || reference == null) {
            log.debug("Skipping {} entry: snapshot/reference not fully populated yet", direction);
            return;
        }

        PaperOrderState order = new PaperOrderState(LocalDate.now(), direction, snapshot.tickTime(),
                snapshot.nifty(), entryPremium, entryStrike, reference, entryType, marketTrend);
        long id = orderPersistenceService.insertOpen(order, properties);
        order.id = id;
        openOrder = order;
        entryBullishStreak = 0;
        entryBearishStreak = 0;
        exitStreak = 0;
        fastEntryCeStreak = 0;
        fastEntryPeStreak = 0;

        snapshotPersistenceService.insert(id, order.tradeDate, snapshot.tickTime(), snapshot.nifty(),
                snapshot.fixedItmCe(), snapshot.fixedItmPe(), ceDivergence30m, peDivergence30m, 0.0, 0.0, "ENTRY");
        log.info("Paper trade ENTRY: direction={} entryType={} time={} entryPremium={} nifty={}",
                direction, entryType, snapshot.tickTime(), entryPremium, snapshot.nifty());
    }

    private void handleOpenOrder(TickSnapshot snapshot, Double ceDivergence30m, Double peDivergence30m) {
        PaperOrderState order = openOrder;
        Double actualPremium = "CE".equals(order.direction) ? snapshot.fixedItmCe() : snapshot.fixedItmPe();

        String event = trackHypotheticalStopLoss(order, actualPremium, snapshot);

        Double anchoredCeDivergence = premiumReferenceService.fixedItmCeDivergenceFrom(order.reference, snapshot);
        Double anchoredPeDivergence = premiumReferenceService.fixedItmPeDivergenceFrom(order.reference, snapshot);

        boolean exitTrigger;
        if ("CE".equals(order.direction)) {
            // CE exit: weaker (closer-to-neutral) reversal threshold than entry, so we exit as soon as the
            // divergence starts fading rather than waiting for a full opposite-signal reversal.
            exitTrigger = anchoredCeDivergence != null && anchoredPeDivergence != null
                    && (ceDivergence30m < properties.getCeExitThreshold() && peDivergence30m > properties.getPeExitThreshold());
        } else {
            // PE exit: mirror of the CE exit condition using the negated exit thresholds.
            exitTrigger = anchoredCeDivergence != null && anchoredPeDivergence != null
                    && (ceDivergence30m > -properties.getCeExitThreshold() && peDivergence30m < -properties.getPeExitThreshold());
        }
        exitStreak = exitTrigger ? exitStreak + 1 : 0;

        Double directionalDivergence = "CE".equals(order.direction) ? anchoredCeDivergence : anchoredPeDivergence;
        boolean trailingExitApplicable = !properties.isDivergenceTrailingExitFastEntryOnly() || "FAST".equals(order.entryType);
        boolean trailingExitTrigger = trailingExitApplicable && updateDivergenceTrailingState(order, directionalDivergence);

        if (exitStreak >= properties.getConfirmationTicks() && actualPremium != null && snapshot.nifty() != null) {
            closePosition(order, snapshot, actualPremium, "DIVERGENCE_REVERSAL");
            event = "EXIT_DIVERGENCE";
            exitStreak = 0;
        } else if (trailingExitTrigger && actualPremium != null && snapshot.nifty() != null) {
            closePosition(order, snapshot, actualPremium, "DIVERGENCE_TRAILING_RETRACEMENT");
            event = "EXIT_DIVERGENCE_TRAILING";
        }

        snapshotPersistenceService.insert(order.id, order.tradeDate, snapshot.tickTime(), snapshot.nifty(),
                snapshot.fixedItmCe(), snapshot.fixedItmPe(), ceDivergence30m, peDivergence30m,
                anchoredCeDivergence, anchoredPeDivergence, event);
    }

    /** Divergence-trailing exit: tracks {@code order.peakDivergence} (the highest order-anchored
     * divergence — CE for a CE trade, PE for a PE trade — seen since entry) and, once divergence starts
     * pulling back from that peak, counts consecutive falling ticks in {@code order.trailingFallStreak}.
     * A new peak (a value higher than the current peak) always resets the fall streak, since the trade is
     * still making fresh highs. Returns {@code true} once both {@link PaperTradingProperties#getDivergenceTrailingConfirmationCount()}
     * consecutive falling ticks and a retracement from the peak of at least
     * {@link PaperTradingProperties#getCeDivergenceTrailingRetracement()} (CE trade) or
     * {@link PaperTradingProperties#getPeDivergenceTrailingRetracement()} (PE trade) have been observed —
     * evaluated every tick alongside (never replacing) the existing plain divergence-reversal exit above.
     * Only called by the caller when {@link PaperTradingProperties#isDivergenceTrailingExitFastEntryOnly()}
     * is {@code false} or {@code order.entryType} is {@code "FAST"}; otherwise the caller skips calling
     * this entirely so peak/streak state is never tracked (and this exit never fires) for a REGULAR trade
     * when scoped to FAST-only. */
    private boolean updateDivergenceTrailingState(PaperOrderState order, Double currentDivergence) {
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
                ? properties.getCeDivergenceTrailingRetracement()
                : properties.getPeDivergenceTrailingRetracement();

        return order.trailingFallStreak >= properties.getDivergenceTrailingConfirmationCount()
                && (order.peakDivergence - currentDivergence) >= retracement;
    }

    /** Updates the highest premium seen since entry and, if not already hit, checks/records the
     * hypothetical (analysis-only) trailing SL. Never changes {@code openOrder}/position state and never
     * places any order — purely bookkeeping for later P&L comparison. Returns {@code "HYPOTHETICAL_SL_HIT"}
     * if the SL was hit on this tick, else {@code null}. */
    private String trackHypotheticalStopLoss(PaperOrderState order, Double actualPremium, TickSnapshot snapshot) {
        if (actualPremium == null) {
            return null;
        }
        if (actualPremium > order.highestPremium) {
            order.highestPremium = actualPremium;
        }
        if (order.hypotheticalSlHit) {
            orderPersistenceService.updateHighestPremium(order.id, order.highestPremium);
            return null;
        }
        double slLevel = order.highestPremium * (1 - properties.getSlPercentage() / 100.0);
        if (actualPremium <= slLevel) {
            order.hypotheticalSlHit = true;
            order.hypotheticalSlExitPrice = actualPremium;
            order.hypotheticalSlExitTime = snapshot.tickTime();
            order.hypotheticalSlPnl = actualPremium - order.entryPremium;
            orderPersistenceService.recordHypotheticalSlHit(order.id, order.highestPremium, actualPremium,
                    snapshot.tickTime(), order.hypotheticalSlPnl);
            return "HYPOTHETICAL_SL_HIT";
        }
        orderPersistenceService.updateHighestPremium(order.id, order.highestPremium);
        return null;
    }

    private void closePosition(PaperOrderState order, TickSnapshot snapshot, double exitPremium, String reason) {
        double pnl = exitPremium - order.entryPremium;
        orderPersistenceService.closeOrder(order.id, snapshot.tickTime(), snapshot.nifty(), exitPremium, reason, pnl);
        log.info("Paper trade EXIT: id={} direction={} time={} exitPremium={} reason={} pnl={}",
                order.id, order.direction, snapshot.tickTime(), exitPremium, reason, pnl);
        openOrder = null;
        eventPublisher.publishEvent(new PaperTradeClosedEvent(order.id, order.direction, snapshot.tickTime()));
    }

    private Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
