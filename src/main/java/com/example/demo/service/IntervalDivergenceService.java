package com.example.demo.service;

import com.example.demo.dto.OptionGreek;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks a separate, interval-based cumulative premium divergence for each tracked label (ATM CE, ATM PE,
 * FIXED ITM CE, FIXED ITM PE), independent of {@link PremiumReferenceService}'s day/30-min reference-based
 * divergence (which always uses the Greeks frozen at the moment a reference was last captured). This
 * service instead compares each label's premium move, interval over interval, against the Taylor-expansion
 * move expected from the <b>live, currently-cached</b> Greeks (see {@link GreeksCacheService}):
 * <pre>
 *     actualPremiumChange   = currentPremium - previousPremium
 *     expectedPremiumChange = delta * spotChange + 0.5 * gamma * spotChange^2 + theta * elapsedDays
 *     intervalDivergence    = actualPremiumChange - expectedPremiumChange
 *     cumulativeDivergence += intervalDivergence
 * </pre>
 * {@code elapsedDays} (not seconds/minutes) is used because Option Greeks' Theta is expressed per day
 * (same convention {@link PremiumReferenceService} relies on), so the time term must be in the same unit.
 * <p>
 * Triggered once per broadcast cycle from {@link TickSnapshotBroadcastService#broadcastLatestSnapshot()},
 * which passes in the very same {@code enriched} map it already builds via {@link
 * PremiumReferenceService#enrich}; this service reads each label's current premium/strike and the live
 * NIFTY spot straight out of that map, computes the four cumulative divergences, and merges them directly
 * back into the same map (keys {@code atmCeCumulativeDivergence}, {@code atmPeCumulativeDivergence},
 * {@code fixedItmCeCumulativeDivergence}, {@code fixedItmPeCumulativeDivergence}) so they flow through to
 * the WebSocket broadcast and the (dynamically-schemaed) {@code enriched_snapshot} table for free, with no
 * further plumbing required.
 * <p>
 * The previous-snapshot/cumulative state for all four labels resets once per trading day (first call of a
 * new {@link LocalDate#now()}). A label's own strike change (e.g. after an ATM/ITM re-selection) does
 * <b>not</b> reset its cumulative total — the running total intentionally survives strike swaps — but the
 * one interval spanning the swap is skipped (comparing a premium under the old strike against the new
 * strike's premium would be meaningless) and that label's baseline is simply resynced to the new strike's
 * current premium instead.
 */
@Service
public class IntervalDivergenceService {

    private static final Logger log = LoggerFactory.getLogger(IntervalDivergenceService.class);

    private static final String[] LABELS = {"ATM CE", "ATM PE", "FIXED ITM CE", "FIXED ITM PE"};
    private static final String[] PREMIUM_KEYS = {"atmCe", "atmPe", "fixedItmCe", "fixedItmPe"};
    private static final String[] STRIKE_KEYS = {"atmCeStrike", "atmPeStrike", "fixedItmCeStrike", "fixedItmPeStrike"};
    private static final String[] CUMULATIVE_KEYS = {
            "atmCeCumulativeDivergence", "atmPeCumulativeDivergence",
            "fixedItmCeCumulativeDivergence", "fixedItmPeCumulativeDivergence"};

    private final GreeksCacheService greeksCacheService;

    /** This label's snapshot (premium, NIFTY spot, strike, tick time) as of the last interval for which a
     * divergence was actually calculated (or, for a brand-new/just-reset label, the baseline to measure
     * the next interval from). */
    private record LabelSnapshot(double premium, double spot, double strike, LocalTime time) {
    }

    private final Map<String, LabelSnapshot> previousByLabel = new ConcurrentHashMap<>();
    private final Map<String, Double> cumulativeDivergenceByLabel = new ConcurrentHashMap<>();
    private volatile LocalDate currentDay;

    public IntervalDivergenceService(GreeksCacheService greeksCacheService) {
        this.greeksCacheService = greeksCacheService;
    }

    /** Computes this cycle's interval divergence for each of the four tracked labels from {@code enriched}
     * (as produced by {@link PremiumReferenceService#enrich}), accumulates it into each label's running
     * cumulative total, and merges the four cumulative totals directly into {@code enriched} under
     * {@code <label>CumulativeDivergence} keys. Does nothing (beyond a possible daily reset) if {@code
     * enriched} is missing {@code nifty} or {@code tickTime}. */
    public void onEnrichedSnapshot(Map<String, Object> enriched) {
        resetIfNewTradingDay();

        Double spot = asDouble(enriched.get("nifty"));
        LocalTime tickTime = enriched.get("tickTime") instanceof LocalTime lt ? lt : null;
        if (spot == null || tickTime == null) {
            mergeCumulativeValues(enriched);
            return;
        }

        for (int i = 0; i < LABELS.length; i++) {
            processLabel(LABELS[i], PREMIUM_KEYS[i], STRIKE_KEYS[i], enriched, spot, tickTime);
        }
        mergeCumulativeValues(enriched);
    }

    private void resetIfNewTradingDay() {
        LocalDate today = LocalDate.now();
        if (currentDay == null || !currentDay.equals(today)) {
            previousByLabel.clear();
            cumulativeDivergenceByLabel.clear();
            currentDay = today;
            log.info("IntervalDivergenceService: new trading day {}; cumulative divergence reset for all labels", today);
        }
    }

    private void processLabel(String label, String premiumKey, String strikeKey, Map<String, Object> enriched,
                               double spot, LocalTime tickTime) {
        Double currentPremium = asDouble(enriched.get(premiumKey));
        Double currentStrike = asDouble(enriched.get(strikeKey));
        if (currentPremium == null || currentStrike == null) {
            return;
        }

        LabelSnapshot previous = previousByLabel.get(label);
        if (previous == null || Double.compare(previous.strike(), currentStrike) != 0) {
            // First observation of the day, or this label's strike just changed (e.g. ATM/ITM
            // re-selection): resync the baseline to the current strike/premium without computing a
            // divergence for this interval, and leave the cumulative total untouched.
            previousByLabel.put(label, new LabelSnapshot(currentPremium, spot, currentStrike, tickTime));
            return;
        }

        OptionGreek greek = greeksCacheService.getGreek(label, currentStrike);
        if (greek == null) {
            // Live Greeks haven't caught up yet (e.g. stale/missing for this strike); skip this interval
            // entirely without updating the baseline, so once Greeks are available again the next
            // interval is measured across the full accumulated gap rather than silently under-counting.
            log.debug("Skipping interval divergence for {}: no live Greek cached for strike={}", label, currentStrike);
            return;
        }

        double spotChange = spot - previous.spot();
        double elapsedDays = Duration.between(previous.time(), tickTime).toMillis() / 86_400_000.0;
        double expectedPremiumChange = greek.delta() * spotChange
                + 0.5 * greek.gamma() * spotChange * spotChange
                + greek.theta() * elapsedDays;
        double actualPremiumChange = currentPremium - previous.premium();
        double intervalDivergence = actualPremiumChange - expectedPremiumChange;

        cumulativeDivergenceByLabel.merge(label, intervalDivergence, Double::sum);
        previousByLabel.put(label, new LabelSnapshot(currentPremium, spot, currentStrike, tickTime));
    }

    private void mergeCumulativeValues(Map<String, Object> enriched) {
        for (int i = 0; i < LABELS.length; i++) {
            enriched.put(CUMULATIVE_KEYS[i], cumulativeDivergenceByLabel.getOrDefault(LABELS[i], 0.0));
        }
    }

    /** Returns an immutable snapshot of every label's current cumulative divergence (labels not yet
     * accumulated for today default to {@code 0.0}, matching what {@link #onEnrichedSnapshot} merges into
     * the enriched map). */
    public Map<String, Double> getCumulativeDivergence() {
        Map<String, Double> result = new java.util.LinkedHashMap<>();
        for (String label : LABELS) {
            result.put(label, cumulativeDivergenceByLabel.getOrDefault(label, 0.0));
        }
        return Map.copyOf(result);
    }

    private Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
