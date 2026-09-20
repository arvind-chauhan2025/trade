package com.example.demo.service;

import com.example.demo.dto.TickSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures two reference points for the trading day and, for every later {@link TickSnapshot}, computes
 * the expected CE/PE premium via a Delta-Gamma-Theta Taylor expansion from each reference, plus the
 * divergence between the actual and expected premium:
 * <ul>
 *     <li><b>Day reference</b> — captured once, from the first complete snapshot at/after 9:15 AM.
 *     Fixed for the rest of the day. Drives the {@code xExpected}/{@code xDivergence} fields.</li>
 *     <li><b>Rolling reference</b> — re-captured every 30 minutes (starting from the same 9:15
 *     snapshot), so it always reflects the state at the start of the current 30-minute window. Drives
 *     the {@code xExpected30m}/{@code xDivergence30m} fields, letting divergence be measured against a
 *     much more recent baseline than the fixed day-open one.</li>
 * </ul>
 * <p>
 * Formula (per side, e.g. CE), identical for both references, only the reference point differs:
 * <pre>
 * spotChange   = currentNifty - referenceNifty
 * elapsedDays  = elapsedMillis / 86_400_000.0
 * expectedCE   = referenceCE + (refDelta * spotChange) + (0.5 * refGamma * spotChange^2) + (refTheta * elapsedDays)
 * divergenceCE = actualCE - expectedCE
 * </pre>
 * Delta/Gamma/Theta used in the formula are the ones captured <b>at that reference</b> (not the
 * live/current Greeks), since the Gamma term already accounts for the curvature of the premium as the
 * spot moves away from the reference; re-evaluating with live Greeks on every tick would double-count
 * that curvature. Theta's sign is preserved as returned by the broker (typically negative), so it
 * naturally decays the expected premium as elapsed time grows. No expected value/divergence is computed
 * until the relevant reference has been captured for the day.
 */
@Service
public class PremiumReferenceService {

    private static final Logger log = LoggerFactory.getLogger(PremiumReferenceService.class);
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final Duration ROLLING_INTERVAL = Duration.ofMinutes(30);

    private final GreeksCacheService greeksCacheService;

    private volatile LocalDate dayReferenceDate;
    private volatile ReferenceData dayReference;
    private volatile LocalDate rollingReferenceDate;
    private volatile ReferenceData rollingReference;

    public PremiumReferenceService(GreeksCacheService greeksCacheService) {
        this.greeksCacheService = greeksCacheService;
    }

    /** Captures/refreshes both reference points as needed from a just-completed snapshot:
     * <ul>
     *     <li>the fixed day reference, once per day, from the first complete snapshot at/after 9:15 AM;</li>
     *     <li>the rolling reference, re-captured every 30 minutes starting from that same first snapshot.</li>
     * </ul>
     * No-op while it's still before 9:15 AM, or if the snapshot isn't fully populated yet. */
    public void captureIfNeeded(TickSnapshot snapshot) {
        captureIfNeeded(snapshot, false);
    }

    /** Same as {@link #captureIfNeeded(TickSnapshot)}, but when {@code ignoreMarketOpenGate} is
     * {@code true}, skips the "only at/after 9:15 AM" check. Intended only for mock/testing snapshots
     * (e.g. {@code snapshot.mock.enabled=true}) generated before market open, so the reference can still
     * be captured and expected/divergence fields populated while testing outside trading hours. */
    public void captureIfNeeded(TickSnapshot snapshot, boolean ignoreMarketOpenGate) {
        LocalDate today = LocalDate.now();
        if (!ignoreMarketOpenGate && snapshot.tickTime().isBefore(MARKET_OPEN)) {
            return;
        }
        if (snapshot.nifty() == null || snapshot.atmCe() == null || snapshot.atmPe() == null
                || snapshot.fixedItmCe() == null || snapshot.fixedItmPe() == null) {
            return;
        }

        boolean dayReferenceMissingToday = dayReferenceDate == null || !dayReferenceDate.equals(today);
        boolean rollingReferenceMissingToday = rollingReferenceDate == null || !rollingReferenceDate.equals(today);
        boolean rollingIntervalElapsed = !rollingReferenceMissingToday
                && Duration.between(rollingReference.time(), snapshot.tickTime()).compareTo(ROLLING_INTERVAL) >= 0;

        if (!dayReferenceMissingToday && !rollingReferenceMissingToday && !rollingIntervalElapsed) {
            return;
        }

        ReferenceData captured = captureReferenceData(snapshot);

        if (dayReferenceMissingToday) {
            dayReference = captured;
            dayReferenceDate = today;
            log.info("Captured day reference at {}: nifty={}, atmCe={}, atmPe={}, fixedItmCe={}, fixedItmPe={}",
                    snapshot.tickTime(), snapshot.nifty(), snapshot.atmCe(), snapshot.atmPe(), snapshot.fixedItmCe(), snapshot.fixedItmPe());
        }
        if (rollingReferenceMissingToday || rollingIntervalElapsed) {
            rollingReference = captured;
            rollingReferenceDate = today;
            log.info("Captured 30-min rolling reference at {}: nifty={}, atmCe={}, atmPe={}, fixedItmCe={}, fixedItmPe={}",
                    snapshot.tickTime(), snapshot.nifty(), snapshot.atmCe(), snapshot.atmPe(), snapshot.fixedItmCe(), snapshot.fixedItmPe());
        }
    }

    /** Force-refreshes the rolling reference right now from the latest complete snapshot, ignoring the
     * 30-minute interval (used to manually reset the rolling baseline, e.g. via the REST endpoint).
     * Returns {@code true} if refreshed, {@code false} if no complete snapshot is available yet to
     * capture from. */
    public boolean refreshRollingReferenceNow(TickSnapshot snapshot) {
        if (snapshot == null || snapshot.nifty() == null || snapshot.atmCe() == null || snapshot.atmPe() == null
                || snapshot.fixedItmCe() == null || snapshot.fixedItmPe() == null) {
            return false;
        }
        rollingReference = captureReferenceData(snapshot);
        rollingReferenceDate = LocalDate.now();
        log.info("Manually refreshed 30-min rolling reference at {}: nifty={}, atmCe={}, atmPe={}, fixedItmCe={}, fixedItmPe={}",
                snapshot.tickTime(), snapshot.nifty(), snapshot.atmCe(), snapshot.atmPe(), snapshot.fixedItmCe(), snapshot.fixedItmPe());
        return true;
    }

    private ReferenceData captureReferenceData(TickSnapshot snapshot) {
        return new ReferenceData(
                snapshot.tickTime(),
                snapshot.nifty(),
                snapshot.atmCe(), greeksCacheService.getDelta("ATM CE"), greeksCacheService.getGamma("ATM CE"), greeksCacheService.getTheta("ATM CE"),
                snapshot.atmPe(), greeksCacheService.getDelta("ATM PE"), greeksCacheService.getGamma("ATM PE"), greeksCacheService.getTheta("ATM PE"),
                snapshot.fixedItmCe(), greeksCacheService.getDelta("FIXED ITM CE"), greeksCacheService.getGamma("FIXED ITM CE"), greeksCacheService.getTheta("FIXED ITM CE"),
                snapshot.fixedItmPe(), greeksCacheService.getDelta("FIXED ITM PE"), greeksCacheService.getGamma("FIXED ITM PE"), greeksCacheService.getTheta("FIXED ITM PE")
        );
    }

    /** Returns a flattened view of {@code snapshot} (every TickSnapshot field) plus, once each reference
     * is available for today: {@code spotChange}/{@code spotChange30m} and, for ATM CE/PE and FIXED ITM
     * CE/PE, the reference Gamma/Theta used, the expected premium and the actual-vs-expected divergence
     * — one set from the fixed 9:15 day reference ({@code xExpected}/{@code xDivergence}) and one set
     * from the rolling 30-minute reference ({@code xExpected30m}/{@code xDivergence30m}). Before a given
     * reference is captured, its fields are present but {@code null}. */
    public Map<String, Object> enrich(TickSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tickTime", snapshot.tickTime());
        result.put("nifty", snapshot.nifty());
        result.put("atmCe", snapshot.atmCe());
        result.put("atmCeStrike", snapshot.atmCeStrike());
        result.put("atmCeDelta", snapshot.atmCeDelta());
        result.put("atmPe", snapshot.atmPe());
        result.put("atmPeStrike", snapshot.atmPeStrike());
        result.put("atmPeDelta", snapshot.atmPeDelta());
        result.put("fixedItmCe", snapshot.fixedItmCe());
        result.put("fixedItmCeStrike", snapshot.fixedItmCeStrike());
        result.put("fixedItmCeDelta", snapshot.fixedItmCeDelta());
        result.put("fixedItmPe", snapshot.fixedItmPe());
        result.put("fixedItmPeStrike", snapshot.fixedItmPeStrike());
        result.put("fixedItmPeDelta", snapshot.fixedItmPeDelta());
        result.put("niftyFut", snapshot.niftyFut());

        applyReference(result, "", dayReference, dayReferenceDate, snapshot);
        applyReference(result, "30m", rollingReference, rollingReferenceDate, snapshot);
        return result;
    }

    private void applyReference(Map<String, Object> result, String suffix, ReferenceData ref, LocalDate refDate, TickSnapshot snapshot) {
        boolean referenceReadyToday = ref != null && refDate != null && refDate.equals(LocalDate.now());
        if (!referenceReadyToday || snapshot.nifty() == null) {
            putSideNulls(result, "atmCe", suffix);
            putSideNulls(result, "atmPe", suffix);
            putSideNulls(result, "fixedItmCe", suffix);
            putSideNulls(result, "fixedItmPe", suffix);
            result.put("spotChange" + suffix, null);
            return;
        }

        double spotChange = snapshot.nifty() - ref.nifty();
        double elapsedDays = Duration.between(ref.time(), snapshot.tickTime()).toMillis() / 86_400_000.0;
        result.put("spotChange" + suffix, spotChange);

        putSide(result, "atmCe", suffix, snapshot.atmCe(), ref.atmCe(), ref.atmCeDelta(), ref.atmCeGamma(), ref.atmCeTheta(), spotChange, elapsedDays);
        putSide(result, "atmPe", suffix, snapshot.atmPe(), ref.atmPe(), ref.atmPeDelta(), ref.atmPeGamma(), ref.atmPeTheta(), spotChange, elapsedDays);
        putSide(result, "fixedItmCe", suffix, snapshot.fixedItmCe(), ref.fixedItmCe(), ref.fixedItmCeDelta(), ref.fixedItmCeGamma(), ref.fixedItmCeTheta(), spotChange, elapsedDays);
        putSide(result, "fixedItmPe", suffix, snapshot.fixedItmPe(), ref.fixedItmPe(), ref.fixedItmPeDelta(), ref.fixedItmPeGamma(), ref.fixedItmPeTheta(), spotChange, elapsedDays);
    }

    private void putSide(Map<String, Object> result, String prefix, String suffix, Double actual, Double referencePremium,
                          Double delta, Double gamma, Double theta, double spotChange, double elapsedDays) {
        result.put(prefix + "Gamma" + suffix, gamma);
        result.put(prefix + "Theta" + suffix, theta);
        if (actual == null || referencePremium == null || delta == null || gamma == null || theta == null) {
            result.put(prefix + "Expected" + suffix, null);
            result.put(prefix + "Divergence" + suffix, null);
            return;
        }
        double expected = referencePremium
                + (delta * spotChange)
                + (0.5 * gamma * spotChange * spotChange)
                + (theta * elapsedDays);
        result.put(prefix + "Expected" + suffix, expected);
        result.put(prefix + "Divergence" + suffix, actual - expected);
        log.debug("{}{}: actual={} expected={} (ref={}, deltaTerm={}, gammaTerm={}, thetaTerm={}, elapsedDays={}, spotChange={}) divergence={}",
                prefix, suffix, actual, expected, referencePremium,
                delta * spotChange, 0.5 * gamma * spotChange * spotChange, theta * elapsedDays,
                elapsedDays, spotChange, actual - expected);
    }

    private void putSideNulls(Map<String, Object> result, String prefix, String suffix) {
        result.put(prefix + "Gamma" + suffix, null);
        result.put(prefix + "Theta" + suffix, null);
        result.put(prefix + "Expected" + suffix, null);
        result.put(prefix + "Divergence" + suffix, null);
    }

    private record ReferenceData(
            LocalTime time,
            double nifty,
            double atmCe, Double atmCeDelta, Double atmCeGamma, Double atmCeTheta,
            double atmPe, Double atmPeDelta, Double atmPeGamma, Double atmPeTheta,
            double fixedItmCe, Double fixedItmCeDelta, Double fixedItmCeGamma, Double fixedItmCeTheta,
            double fixedItmPe, Double fixedItmPeDelta, Double fixedItmPeGamma, Double fixedItmPeTheta
    ) {
    }
}
