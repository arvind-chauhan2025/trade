package com.example.demo.service;

import com.example.demo.config.PaperTradingProperties;
import com.example.demo.dto.TickSnapshot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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
 *     reversal from the trade's own entry point.</li>
 *     <li><b>Hypothetical SL</b> — analysis only. A trailing stop (percentage below the highest premium
 *     seen since entry) is tracked and recorded ({@code hypotheticalSlHit}/{@code ExitPrice}/{@code ExitTime})
 *     purely for later P&amp;L comparison against the real divergence-based exit. It never changes
 *     position state and never places any order.</li>
 * </ul>
 * Every tick a trade is open, its NIFTY price, FIXED ITM CE/PE premiums, global 30-minute divergence and
 * order-anchored divergence are persisted via {@link OrderSnapshotPersistenceService} for full auditability.
 */
@Service
public class PaperTradingService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingService.class);

    private final PremiumReferenceService premiumReferenceService;
    private final PaperOrderPersistenceService orderPersistenceService;
    private final OrderSnapshotPersistenceService snapshotPersistenceService;
    private final PaperTradingProperties properties;

    private volatile PaperOrderState openOrder;
    private int entryBullishStreak;
    private int entryBearishStreak;
    private int exitStreak;

    public PaperTradingService(PremiumReferenceService premiumReferenceService,
                                PaperOrderPersistenceService orderPersistenceService,
                                OrderSnapshotPersistenceService snapshotPersistenceService,
                                PaperTradingProperties properties) {
        this.premiumReferenceService = premiumReferenceService;
        this.orderPersistenceService = orderPersistenceService;
        this.snapshotPersistenceService = snapshotPersistenceService;
        this.properties = properties;
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
     * {@link PaperTradingProperties#isEnabled()} is {@code false}. */
    public synchronized void onSnapshot(TickSnapshot snapshot, Map<String, Object> enriched) {
        if (!properties.isEnabled() || snapshot == null || snapshot.tickTime() == null) {
            return;
        }
        Double ceDivergence30m = asDouble(enriched.get("fixedItmCeDivergence30m"));
        Double peDivergence30m = asDouble(enriched.get("fixedItmPeDivergence30m"));

        if (openOrder == null) {
            handleEntry(snapshot, ceDivergence30m, peDivergence30m);
        } else {
            handleOpenOrder(snapshot, ceDivergence30m, peDivergence30m);
        }
    }

    private void handleEntry(TickSnapshot snapshot, Double ceDivergence30m, Double peDivergence30m) {
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
            openPosition("CE", snapshot, ceDivergence30m, peDivergence30m);
        } else if (entryBearishStreak >= properties.getConfirmationTicks()) {
            openPosition("PE", snapshot, ceDivergence30m, peDivergence30m);
        }
    }

    private void openPosition(String direction, TickSnapshot snapshot, Double ceDivergence30m, Double peDivergence30m) {
        Double entryPremium = "CE".equals(direction) ? snapshot.fixedItmCe() : snapshot.fixedItmPe();
        Double entryStrike = "CE".equals(direction) ? snapshot.fixedItmCeStrike() : snapshot.fixedItmPeStrike();
        PremiumReferenceService.FixedItmOrderReference reference = premiumReferenceService.captureFixedItmOrderReference(snapshot);
        if (entryPremium == null || snapshot.nifty() == null || reference == null) {
            log.debug("Skipping {} entry: snapshot/reference not fully populated yet", direction);
            return;
        }

        PaperOrderState order = new PaperOrderState(LocalDate.now(), direction, snapshot.tickTime(),
                snapshot.nifty(), entryPremium, entryStrike, reference);
        long id = orderPersistenceService.insertOpen(order);
        order.id = id;
        openOrder = order;
        entryBullishStreak = 0;
        entryBearishStreak = 0;
        exitStreak = 0;

        snapshotPersistenceService.insert(id, order.tradeDate, snapshot.tickTime(), snapshot.nifty(),
                snapshot.fixedItmCe(), snapshot.fixedItmPe(), ceDivergence30m, peDivergence30m, 0.0, 0.0, "ENTRY");
        log.info("Paper trade ENTRY: direction={} time={} entryPremium={} nifty={}",
                direction, snapshot.tickTime(), entryPremium, snapshot.nifty());
    }

    private void handleOpenOrder(TickSnapshot snapshot, Double ceDivergence30m, Double peDivergence30m) {
        PaperOrderState order = openOrder;
        Double actualPremium = "CE".equals(order.direction) ? snapshot.fixedItmCe() : snapshot.fixedItmPe();

        String event = trackHypotheticalStopLoss(order, actualPremium, snapshot);

        Double anchoredCeDivergence = premiumReferenceService.fixedItmCeDivergenceFrom(order.reference, snapshot);
        Double anchoredPeDivergence = premiumReferenceService.fixedItmPeDivergenceFrom(order.reference, snapshot);

        boolean exitTrigger;
        if ("CE".equals(order.direction)) {
            // CE bearish/divergence-reversal confirmation.
            exitTrigger = anchoredCeDivergence != null && anchoredPeDivergence != null
                    && anchoredCeDivergence < properties.getCeBearishThreshold() && anchoredPeDivergence > properties.getPeBearishThreshold();
        } else {
            // PE bullish/divergence-reversal confirmation.
            exitTrigger = anchoredCeDivergence != null && anchoredPeDivergence != null
                    && anchoredCeDivergence > properties.getCeBullishThreshold() && anchoredPeDivergence < properties.getPeBullishThreshold();
        }
        exitStreak = exitTrigger ? exitStreak + 1 : 0;

        if (exitStreak >= properties.getConfirmationTicks() && actualPremium != null && snapshot.nifty() != null) {
            closePosition(order, snapshot, actualPremium, "DIVERGENCE_REVERSAL");
            event = "EXIT_DIVERGENCE";
            exitStreak = 0;
        }

        snapshotPersistenceService.insert(order.id, order.tradeDate, snapshot.tickTime(), snapshot.nifty(),
                snapshot.fixedItmCe(), snapshot.fixedItmPe(), ceDivergence30m, peDivergence30m,
                anchoredCeDivergence, anchoredPeDivergence, event);
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
    }

    private Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
