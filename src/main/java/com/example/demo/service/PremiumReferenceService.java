package com.example.demo.service;

import com.example.demo.dto.TickSnapshot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>
 * {@code TradingApplication} also re-selects the actual ATM/FIXED ITM CE/PE <b>strikes</b> every 30
 * minutes as spot moves (held fixed within each window) and calls {@link #resetRollingReference()}
 * whenever a leg's strike changes. Each reference point also records the strike it was captured at, so
 * {@link #applyReference} can detect and suppress expected/divergence output for the brief gap between a
 * strike change and the next reference recapture, rather than comparing a captured strike (e.g. 23400)
 * against a now-current different strike (e.g. 23550).
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
                snapshot.atmCe(), snapshot.atmCeStrike(), greeksCacheService.getDelta("ATM CE"), greeksCacheService.getGamma("ATM CE"), greeksCacheService.getTheta("ATM CE"),
                snapshot.atmPe(), snapshot.atmPeStrike(), greeksCacheService.getDelta("ATM PE"), greeksCacheService.getGamma("ATM PE"), greeksCacheService.getTheta("ATM PE"),
                snapshot.fixedItmCe(), snapshot.fixedItmCeStrike(), greeksCacheService.getDelta("FIXED ITM CE"), greeksCacheService.getGamma("FIXED ITM CE"), greeksCacheService.getTheta("FIXED ITM CE"),
                snapshot.fixedItmPe(), snapshot.fixedItmPeStrike(), greeksCacheService.getDelta("FIXED ITM PE"), greeksCacheService.getGamma("FIXED ITM PE"), greeksCacheService.getTheta("FIXED ITM PE")
        );
    }

    /**
     * Invalidates the rolling reference immediately, without waiting for the 30-minute interval to
     * elapse. Called by {@code TradingApplication} whenever the 30-minute rolling ATM/ITM re-selection
     * swaps in a different strike for any leg (e.g. ATM was 23400, spot moved and the new window's ATM is
     * 23550): the old reference's premium belongs to a now-abandoned strike, so comparing the new
     * strike's live premium against it would be meaningless. Clearing it here means {@link #applyReference}
     * reports {@code null} expected/divergence values (rather than a misleading number) until
     * {@link #captureIfNeeded} recaptures a fresh reference — at the new strikes — from the very next
     * complete snapshot.
     */
    public void resetRollingReference() {
        rollingReference = null;
        rollingReferenceDate = null;
        log.info("Rolling reference invalidated (ATM/ITM re-selection changed strikes); will recapture from next complete snapshot");
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

        putSide(result, "atmCe", suffix, snapshot.atmCe(), snapshot.atmCeStrike(), ref.atmCe(), ref.atmCeStrike(), ref.atmCeDelta(), ref.atmCeGamma(), ref.atmCeTheta(), spotChange, elapsedDays);
        putSide(result, "atmPe", suffix, snapshot.atmPe(), snapshot.atmPeStrike(), ref.atmPe(), ref.atmPeStrike(), ref.atmPeDelta(), ref.atmPeGamma(), ref.atmPeTheta(), spotChange, elapsedDays);
        putSide(result, "fixedItmCe", suffix, snapshot.fixedItmCe(), snapshot.fixedItmCeStrike(), ref.fixedItmCe(), ref.fixedItmCeStrike(), ref.fixedItmCeDelta(), ref.fixedItmCeGamma(), ref.fixedItmCeTheta(), spotChange, elapsedDays);
        putSide(result, "fixedItmPe", suffix, snapshot.fixedItmPe(), snapshot.fixedItmPeStrike(), ref.fixedItmPe(), ref.fixedItmPeStrike(), ref.fixedItmPeDelta(), ref.fixedItmPeGamma(), ref.fixedItmPeTheta(), spotChange, elapsedDays);
    }

    private void putSide(Map<String, Object> result, String prefix, String suffix, Double actual, Double actualStrike,
                          Double referencePremium, Double referenceStrike,
                          Double delta, Double gamma, Double theta, double spotChange, double elapsedDays) {
        result.put(prefix + "Gamma" + suffix, gamma);
        result.put(prefix + "Theta" + suffix, theta);
        if (actual == null || referencePremium == null || delta == null || gamma == null || theta == null) {
            result.put(prefix + "Expected" + suffix, null);
            result.put(prefix + "Divergence" + suffix, null);
            return;
        }
        // Guards against comparing across a strike change (e.g. rolling ATM re-selection swapped 23400
        // for 23550 mid-window, but this reference/snapshot pair hasn't been refreshed for it yet):
        // the reference's premium belongs to a different instrument than the current tick, so the
        // Taylor-expansion math below would be comparing unrelated strikes. Suppress rather than mislead.
        if (actualStrike == null || referenceStrike == null || Double.compare(actualStrike, referenceStrike) != 0) {
            result.put(prefix + "Expected" + suffix, null);
            result.put(prefix + "Divergence" + suffix, null);
            log.debug("{}{}: skipping expected/divergence, strike mismatch (referenceStrike={}, actualStrike={})",
                    prefix, suffix, referenceStrike, actualStrike);
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
            double atmCe, Double atmCeStrike, Double atmCeDelta, Double atmCeGamma, Double atmCeTheta,
            double atmPe, Double atmPeStrike, Double atmPeDelta, Double atmPeGamma, Double atmPeTheta,
            double fixedItmCe, Double fixedItmCeStrike, Double fixedItmCeDelta, Double fixedItmCeGamma, Double fixedItmCeTheta,
            double fixedItmPe, Double fixedItmPeStrike, Double fixedItmPeDelta, Double fixedItmPeGamma, Double fixedItmPeTheta
    ) {
    }
}
