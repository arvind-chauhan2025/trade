package com.example.demo.service;

import com.example.demo.dto.OptionContract;
import com.example.demo.dto.TickSnapshot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
 * Both reference points are also durably persisted to the {@code premium_reference} Postgres table (tagged
 * with {@code tick_date} and a {@code day}/{@code rolling} type) on every capture. Every capture
 * <b>appends a new row</b> rather than overwriting or deleting the previous one — whether it's the 9:15 AM
 * day reference, a scheduled 30-minute rolling recapture, an ATM/ITM-triggered recapture, or a manual
 * refresh via the REST endpoint — so the full history of references captured today (and on prior days)
 * stays in the table. Only the most recent, non-invalidated row per type/day is restored on startup
 * ({@link #init()}/{@link #restoreFromDatabase()}) or considered "current"; {@link #resetRollingReference()}
 * marks a row {@code invalidated} instead of deleting it when ATM/ITM re-selection makes it stale. This
 * means a machine/app restart mid-day does <b>not</b> flush today's references and force a later,
 * artificial re-baseline — the original 9:15 AM day reference (and whichever 30-minute window the rolling
 * reference was captured in) survives the restart intact.
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
    private static final String DAY_REF_TYPE = "day";
    private static final String ROLLING_REF_TYPE = "rolling";

    private final GreeksCacheService greeksCacheService;
    private final JdbcTemplate jdbcTemplate;
    /** Set {@code true} by {@link #resetRollingReference()} and consumed (read-then-cleared) by the very
     * next {@link #enrich} call, so exactly one enriched snapshot/broadcast cycle after an ATM/ITM
     * strike-driven rolling reference reset reports {@code resetAtmItm=true}; every other cycle reports
     * {@code false}. */
    private final AtomicBoolean resetAtmItmFlag = new AtomicBoolean(false);

    private volatile LocalDate dayReferenceDate;
    private volatile ReferenceData dayReference;
    private volatile LocalDate rollingReferenceDate;
    private volatile ReferenceData rollingReference;
    private volatile PaperTradingService paperTradingService;
    /** The strikes {@code TradingApplication} most recently (re)selected (at startup or a 30-minute
     * re-selection), set via {@link #setExpectedStrikes}. {@link #captureIfNeeded} refuses to capture a
     * reference from a snapshot unless every leg's strike matches this, guarding against a snapshot that's
     * "complete" (every field non-null) yet still has one leg's *previous* strike's premium because that
     * leg hasn't ticked under its new subscription yet. {@code null} means no selection has been recorded
     * yet (e.g. mock/test snapshots bypassing {@code TradingApplication} entirely) — in that case the
     * check is skipped rather than blocking capture forever. */
    private volatile ExpectedStrikes expectedStrikes;

    private record ExpectedStrikes(double atmCe, double atmPe, double fixedItmCe, double fixedItmPe) {
    }

    // @Lazy breaks the PremiumReferenceService <-> PaperTradingService constructor-injection cycle:
    // PaperTradingService needs a fully-constructed PremiumReferenceService, and PremiumReferenceService
    // only needs PaperTradingService for the occasional hasOpenPosition() check below, never during
    // construction, so a lazy proxy here is sufficient and avoids Spring's "dependencies form a cycle" error.
    public PremiumReferenceService(GreeksCacheService greeksCacheService, DataSource dataSource,
                                    @Lazy PaperTradingService paperTradingService) {
        this.greeksCacheService = greeksCacheService;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.paperTradingService = paperTradingService;
    }

    /** Creates the {@code premium_reference} table if needed, then restores today's day/rolling
     * references from it (if any were persisted earlier today), so a restart doesn't flush them. */
    @PostConstruct
    void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS premium_reference (
                    tick_date DATE NOT NULL,
                    ref_type VARCHAR(16) NOT NULL,
                    tick_time VARCHAR(16) NOT NULL,
                    captured_at TIMESTAMP NOT NULL DEFAULT now(),
                    invalidated BOOLEAN NOT NULL DEFAULT FALSE,
                    nifty DOUBLE PRECISION NOT NULL,
                    atm_ce DOUBLE PRECISION NOT NULL, atm_ce_strike DOUBLE PRECISION,
                    atm_ce_delta DOUBLE PRECISION, atm_ce_gamma DOUBLE PRECISION, atm_ce_theta DOUBLE PRECISION,
                    atm_pe DOUBLE PRECISION NOT NULL, atm_pe_strike DOUBLE PRECISION,
                    atm_pe_delta DOUBLE PRECISION, atm_pe_gamma DOUBLE PRECISION, atm_pe_theta DOUBLE PRECISION,
                    fixed_itm_ce DOUBLE PRECISION NOT NULL, fixed_itm_ce_strike DOUBLE PRECISION,
                    fixed_itm_ce_delta DOUBLE PRECISION, fixed_itm_ce_gamma DOUBLE PRECISION, fixed_itm_ce_theta DOUBLE PRECISION,
                    fixed_itm_pe DOUBLE PRECISION NOT NULL, fixed_itm_pe_strike DOUBLE PRECISION,
                    fixed_itm_pe_delta DOUBLE PRECISION, fixed_itm_pe_gamma DOUBLE PRECISION, fixed_itm_pe_theta DOUBLE PRECISION
                )
                """);
        // Migration for tables created before history-keeping was added: drop the old (tick_date, ref_type)
        // primary key (which forced one row per type per day and made every recapture overwrite the
        // previous one via upsert) and add the new bookkeeping columns, so every capture from here on
        // appends a new row instead of overwriting/deleting the prior reference.
        jdbcTemplate.execute("ALTER TABLE premium_reference DROP CONSTRAINT IF EXISTS premium_reference_pkey");
        jdbcTemplate.execute("ALTER TABLE premium_reference ADD COLUMN IF NOT EXISTS captured_at TIMESTAMP NOT NULL DEFAULT now()");
        jdbcTemplate.execute("ALTER TABLE premium_reference ADD COLUMN IF NOT EXISTS invalidated BOOLEAN NOT NULL DEFAULT FALSE");
        restoreFromDatabase();
    }

    private record PersistedReference(String type, ReferenceData data) {
    }

    private void restoreFromDatabase() {
        LocalDate today = LocalDate.now();
        try {
            // One row per ref_type: the most recently captured, still-valid (not invalidated) reference for
            // today. History (every earlier capture) stays in the table untouched; we just need today's
            // current one per type to resume from after a restart.
            List<PersistedReference> rows = jdbcTemplate.query(
                    """
                    SELECT DISTINCT ON (ref_type) * FROM premium_reference
                    WHERE tick_date = ? AND invalidated = FALSE
                    ORDER BY ref_type, captured_at DESC
                    """,
                    (rs, rowNum) -> new PersistedReference(rs.getString("ref_type"), mapRow(rs)),
                    today);
            for (PersistedReference row : rows) {
                if (DAY_REF_TYPE.equals(row.type())) {
                    dayReference = row.data();
                    dayReferenceDate = today;
                    log.info("Restored day reference from database (originally captured at {}): nifty={}",
                            row.data().time(), row.data().nifty());
                } else if (ROLLING_REF_TYPE.equals(row.type())) {
                    rollingReference = row.data();
                    rollingReferenceDate = today;
                    log.info("Restored 30-min rolling reference from database (originally captured at {}): nifty={}",
                            row.data().time(), row.data().nifty());
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to restore premium reference(s) from database; will recapture from next complete snapshot", ex);
        }
    }

    private ReferenceData mapRow(ResultSet rs) throws SQLException {
        return new ReferenceData(
                LocalTime.parse(rs.getString("tick_time")),
                rs.getDouble("nifty"),
                rs.getDouble("atm_ce"), nullableDouble(rs, "atm_ce_strike"), nullableDouble(rs, "atm_ce_delta"), nullableDouble(rs, "atm_ce_gamma"), nullableDouble(rs, "atm_ce_theta"),
                rs.getDouble("atm_pe"), nullableDouble(rs, "atm_pe_strike"), nullableDouble(rs, "atm_pe_delta"), nullableDouble(rs, "atm_pe_gamma"), nullableDouble(rs, "atm_pe_theta"),
                rs.getDouble("fixed_itm_ce"), nullableDouble(rs, "fixed_itm_ce_strike"), nullableDouble(rs, "fixed_itm_ce_delta"), nullableDouble(rs, "fixed_itm_ce_gamma"), nullableDouble(rs, "fixed_itm_ce_theta"),
                rs.getDouble("fixed_itm_pe"), nullableDouble(rs, "fixed_itm_pe_strike"), nullableDouble(rs, "fixed_itm_pe_delta"), nullableDouble(rs, "fixed_itm_pe_gamma"), nullableDouble(rs, "fixed_itm_pe_theta")
        );
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /** Inserts {@code data} as a brand-new persisted reference row of {@code type} for {@code date}, so it
     * survives a restart. Unlike the previous upsert-by-(tick_date, ref_type) behavior, this never
     * overwrites or deletes an earlier capture — every 30-minute rolling recapture (whether from the
     * scheduled re-selection or the manual refresh endpoint) appends its own row with its own
     * {@code tick_time}/{@code captured_at}, so the full history of references captured today is kept in
     * the table. Only {@link #restoreFromDatabase()} and {@link #resetRollingReference()} care about which
     * row is "current" per type, via {@code invalidated}/{@code captured_at}. Persistence failures are
     * logged but non-fatal — the in-memory reference stays authoritative for the rest of this process's
     * lifetime either way. */
    private void persist(String type, LocalDate date, ReferenceData data) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO premium_reference (
                        tick_date, ref_type, tick_time,
                        atm_ce, atm_ce_strike, atm_ce_delta, atm_ce_gamma, atm_ce_theta,
                        atm_pe, atm_pe_strike, atm_pe_delta, atm_pe_gamma, atm_pe_theta,
                        fixed_itm_ce, fixed_itm_ce_strike, fixed_itm_ce_delta, fixed_itm_ce_gamma, fixed_itm_ce_theta,
                        fixed_itm_pe, fixed_itm_pe_strike, fixed_itm_pe_delta, fixed_itm_pe_gamma, fixed_itm_pe_theta,
                        nifty
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    date, type, data.time().toString(),
                    data.atmCe(), data.atmCeStrike(), data.atmCeDelta(), data.atmCeGamma(), data.atmCeTheta(),
                    data.atmPe(), data.atmPeStrike(), data.atmPeDelta(), data.atmPeGamma(), data.atmPeTheta(),
                    data.fixedItmCe(), data.fixedItmCeStrike(), data.fixedItmCeDelta(), data.fixedItmCeGamma(), data.fixedItmCeTheta(),
                    data.fixedItmPe(), data.fixedItmPeStrike(), data.fixedItmPeDelta(), data.fixedItmPeGamma(), data.fixedItmPeTheta(),
                    data.nifty());
        } catch (Exception ex) {
            log.warn("Failed to persist {} reference to database (in-memory value is still active; will retry on next capture)", type, ex);
        }
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

        if (!matchesExpectedStrikes(snapshot)) {
            // A multi-leg ATM/ITM re-selection (or a restart that picked different strikes) doesn't
            // guarantee every leg's first tick under the new strike arrives at the same instant: this
            // snapshot can be "complete" (every field non-null) yet still carry one leg's *previous*
            // strike's premium because that leg simply hasn't ticked under the new subscription yet.
            // Capturing it now would silently bake a stale strike's premium into the reference for the
            // rest of this 30-minute window. Skip and let the very next complete snapshot try again —
            // once every leg matches, this check passes and the capture proceeds normally.
            log.debug("Skipping reference capture at {}: snapshot strikes don't yet all match the latest "
                    + "ATM/ITM selection (still waiting for some leg's first tick under its new strike)",
                    snapshot.tickTime());
            return;
        }

        ReferenceData captured = captureReferenceData(snapshot);

        if (dayReferenceMissingToday) {
            dayReference = captured;
            dayReferenceDate = today;
            persist(DAY_REF_TYPE, today, captured);
            log.info("Captured day reference at {}: nifty={}, atmCe={}, atmPe={}, fixedItmCe={}, fixedItmPe={}",
                    snapshot.tickTime(), snapshot.nifty(), snapshot.atmCe(), snapshot.atmPe(), snapshot.fixedItmCe(), snapshot.fixedItmPe());
        }
        if (rollingReferenceMissingToday || rollingIntervalElapsed) {
            if (paperTradingService.hasOpenPosition()) {
                log.debug("30-min rolling window elapsed but a paper trade is currently open; rollingReference until it closes");
                return;
            }
            rollingReference = captured;
            rollingReferenceDate = today;
            persist(ROLLING_REF_TYPE, today, captured);
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
        resetAtmItmFlag.set(true);
        persist(ROLLING_REF_TYPE, rollingReferenceDate, rollingReference);
        log.info("Manually refreshed 30-min rolling reference at {}: nifty={}, atmCe={}, atmPe={}, fixedItmCe={}, fixedItmPe={}",
                snapshot.tickTime(), snapshot.nifty(), snapshot.atmCe(), snapshot.atmPe(), snapshot.fixedItmCe(), snapshot.fixedItmPe());
        return true;
    }

    private ReferenceData captureReferenceData(TickSnapshot snapshot) {
        return new ReferenceData(
                snapshot.tickTime(),
                snapshot.nifty(),
                snapshot.atmCe(), snapshot.atmCeStrike(), greeksCacheService.getDelta("ATM CE", snapshot.atmCeStrike()), greeksCacheService.getGamma("ATM CE", snapshot.atmCeStrike()), greeksCacheService.getTheta("ATM CE", snapshot.atmCeStrike()),
                snapshot.atmPe(), snapshot.atmPeStrike(), greeksCacheService.getDelta("ATM PE", snapshot.atmPeStrike()), greeksCacheService.getGamma("ATM PE", snapshot.atmPeStrike()), greeksCacheService.getTheta("ATM PE", snapshot.atmPeStrike()),
                snapshot.fixedItmCe(), snapshot.fixedItmCeStrike(), greeksCacheService.getDelta("FIXED ITM CE", snapshot.fixedItmCeStrike()), greeksCacheService.getGamma("FIXED ITM CE", snapshot.fixedItmCeStrike()), greeksCacheService.getTheta("FIXED ITM CE", snapshot.fixedItmCeStrike()),
                snapshot.fixedItmPe(), snapshot.fixedItmPeStrike(), greeksCacheService.getDelta("FIXED ITM PE", snapshot.fixedItmPeStrike()), greeksCacheService.getGamma("FIXED ITM PE", snapshot.fixedItmPeStrike()), greeksCacheService.getTheta("FIXED ITM PE", snapshot.fixedItmPeStrike())
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
        resetAtmItmFlag.set(true);
        try {
            // Mark rather than delete: keeps this now-abandoned-strike capture in the history table instead
            // of erasing it, while still ensuring a restart before the next capture won't restore it (see
            // restoreFromDatabase(), which only considers invalidated = FALSE rows).
            jdbcTemplate.update("UPDATE premium_reference SET invalidated = TRUE WHERE ref_type = ? AND tick_date = ? AND invalidated = FALSE",
                    ROLLING_REF_TYPE, LocalDate.now());
        } catch (Exception ex) {
            log.warn("Failed to invalidate persisted rolling reference row; a restart before the next capture "
                    + "could otherwise restore the now-invalidated strike's reference", ex);
        }
        log.info("Rolling reference invalidated (ATM/ITM re-selection changed strikes); will recapture from next complete snapshot");
    }

    /** Called by {@code TradingApplication.startPipeline()} right after a (re)start's fresh ATM/FIXED ITM
     * selection. A restart always re-derives strikes from the current spot price (never from a previous
     * day/session), but {@link #init()} separately restores whatever rolling reference was persisted
     * <b>before</b> the restart — which may belong to a now-different strike (e.g. restarted mid-day
     * after spot moved). Without this check, that stale reference would sit mismatched against the new
     * strike until its own unrelated 30-minute timer happens to elapse (see {@link #resetRollingReference()}),
     * leaving divergence null for however long that gap is. No-op if no rolling reference was restored, or
     * if its strikes already match the fresh selection. */
    public void resetRollingReferenceIfStaleFor(OptionContract atmCe, OptionContract atmPe,
                                                 OptionContract fixedItmCe, OptionContract fixedItmPe) {
        ReferenceData ref = rollingReference;
        if (ref == null) {
            return;
        }
        boolean mismatched = !sameStrike(ref.atmCeStrike(), atmCe.strike())
                || !sameStrike(ref.atmPeStrike(), atmPe.strike())
                || !sameStrike(ref.fixedItmCeStrike(), fixedItmCe.strike())
                || !sameStrike(ref.fixedItmPeStrike(), fixedItmPe.strike());
        if (mismatched) {
            log.info("Restored rolling reference strikes don't match this (re)start's freshly selected "
                    + "ATM/ITM strikes; invalidating so a stale strike's premium is never compared against the new one");
            resetRollingReference();
        }
    }

    private static boolean sameStrike(Double referenceStrike, double actualStrike) {
        return referenceStrike != null && Double.compare(referenceStrike, actualStrike) == 0;
    }

    /** Records the strikes {@code TradingApplication} just selected (at startup, in {@code startPipeline()},
     * and on every 30-minute re-selection), so {@link #captureIfNeeded} can verify a candidate snapshot is
     * fully consistent with them before trusting it as a fresh reference. Call this <i>before</i> {@link
     * #resetRollingReference()}/{@link #resetRollingReferenceIfStaleFor} so the very next capture attempt
     * already validates against the up-to-date target. */
    public void setExpectedStrikes(OptionContract atmCe, OptionContract atmPe,
                                    OptionContract fixedItmCe, OptionContract fixedItmPe) {
        this.expectedStrikes = new ExpectedStrikes(atmCe.strike(), atmPe.strike(), fixedItmCe.strike(), fixedItmPe.strike());
    }

    /** Returns {@code true} if every leg in {@code snapshot} matches the last-recorded {@link
     * #setExpectedStrikes} target (or if no target has been recorded yet, e.g. mock/test snapshots that
     * bypass {@code TradingApplication} entirely — in which case the check is skipped rather than
     * blocking capture forever). */
    private boolean matchesExpectedStrikes(TickSnapshot snapshot) {
        ExpectedStrikes expected = expectedStrikes;
        if (expected == null) {
            return true;
        }
        return sameStrike(snapshot.atmCeStrike(), expected.atmCe())
                && sameStrike(snapshot.atmPeStrike(), expected.atmPe())
                && sameStrike(snapshot.fixedItmCeStrike(), expected.fixedItmCe())
                && sameStrike(snapshot.fixedItmPeStrike(), expected.fixedItmPe());
    }

    /** Returns a flattened view of {@code snapshot} (every TickSnapshot field) plus, once each reference
     * is available for today: {@code spotChange}/{@code spotChange30m} and, for ATM CE/PE and FIXED ITM
     * CE/PE, the strike the reference was captured at, the reference Gamma/Theta used, the expected
     * premium and the actual-vs-expected divergence — one set from the fixed 9:15 day reference
     * ({@code xRefStrike}/{@code xExpected}/{@code xDivergence}) and one set from the rolling 30-minute
     * reference ({@code xRefStrike30m}/{@code xExpected30m}/{@code xDivergence30m}). Before a given
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
        result.put("rollingRefTime", rollingReference.time());
        // true only for the one enriched snapshot right after resetRollingReference() fired (an ATM/ITM
        // strike change), false on every other cycle.
        result.put("resetAtmItm", resetAtmItmFlag.getAndSet(false));

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
        result.put(prefix + "RefStrike" + suffix, referenceStrike);
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
        double expected = expectedPremium(referencePremium, delta, gamma, theta, spotChange, elapsedDays);
        result.put(prefix + "Expected" + suffix, expected);
        result.put(prefix + "Divergence" + suffix, actual - expected);
        log.debug("{}{}: actual={} expected={} (ref={}, deltaTerm={}, gammaTerm={}, thetaTerm={}, elapsedDays={}, spotChange={}) divergence={}",
                prefix, suffix, actual, expected, referencePremium,
                delta * spotChange, 0.5 * gamma * spotChange * spotChange, theta * elapsedDays,
                elapsedDays, spotChange, actual - expected);
    }

    /** The shared Delta-Gamma-Theta Taylor expansion formula (documented on the class), factored out so
     * both {@link #putSide} (day/rolling reference divergence, per-tick enrichment) and the
     * order-anchored divergence used by paper-trading exit checks ({@link #fixedItmCeDivergenceFrom} /
     * {@link #fixedItmPeDivergenceFrom}) compute expected premium identically, without duplicating the
     * math. */
    private static double expectedPremium(double referencePremium, double delta, double gamma, double theta,
                                           double spotChange, double elapsedDays) {
        return referencePremium
                + (delta * spotChange)
                + (0.5 * gamma * spotChange * spotChange)
                + (theta * elapsedDays);
    }

    /** A FIXED ITM CE/PE reference point anchored at a specific moment (e.g. a paper-trade entry), kept
     * independent of the globally shared 30-minute {@link #rollingReference} (which keeps moving every 30
     * minutes and would otherwise make a trade's exit divergence be measured against a baseline that has
     * nothing to do with the trade's entry). Captured once via {@link #captureFixedItmOrderReference} and
     * then reused for the lifetime of that trade with {@link #fixedItmCeDivergenceFrom} /
     * {@link #fixedItmPeDivergenceFrom}. */
    public record FixedItmOrderReference(
            LocalTime time,
            double nifty,
            double ce, Double ceStrike, Double ceDelta, Double ceGamma, Double ceTheta,
            double pe, Double peStrike, Double peDelta, Double peGamma, Double peTheta
    ) {
    }

    /** Captures a {@link FixedItmOrderReference} from the current FIXED ITM CE/PE premiums/strikes and
     * currently-cached Greeks, to be anchored to a single paper trade (independent of the shared rolling
     * reference). Returns {@code null} if the snapshot or cached Greeks aren't fully populated yet. */
    public FixedItmOrderReference captureFixedItmOrderReference(TickSnapshot snapshot) {
        if (snapshot == null || snapshot.nifty() == null || snapshot.fixedItmCe() == null || snapshot.fixedItmPe() == null) {
            return null;
        }
        return new FixedItmOrderReference(
                snapshot.tickTime(), snapshot.nifty(),
                snapshot.fixedItmCe(), snapshot.fixedItmCeStrike(),
                greeksCacheService.getDelta("FIXED ITM CE", snapshot.fixedItmCeStrike()), greeksCacheService.getGamma("FIXED ITM CE", snapshot.fixedItmCeStrike()), greeksCacheService.getTheta("FIXED ITM CE", snapshot.fixedItmCeStrike()),
                snapshot.fixedItmPe(), snapshot.fixedItmPeStrike(),
                greeksCacheService.getDelta("FIXED ITM PE", snapshot.fixedItmPeStrike()), greeksCacheService.getGamma("FIXED ITM PE", snapshot.fixedItmPeStrike()), greeksCacheService.getTheta("FIXED ITM PE", snapshot.fixedItmPeStrike())
        );
    }

    /** Divergence of the current FIXED ITM CE premium vs. {@code ref} (an order-anchored reference from
     * {@link #captureFixedItmOrderReference}), using the same Taylor-expansion formula as the day/rolling
     * references. Returns {@code null} if the reference/snapshot/Greeks aren't available, or if the
     * FIXED ITM CE strike has changed since the reference was captured (comparing across a strike change
     * would be meaningless). */
    public Double fixedItmCeDivergenceFrom(FixedItmOrderReference ref, TickSnapshot snapshot) {
        if (ref == null || snapshot == null || snapshot.nifty() == null || snapshot.fixedItmCe() == null
                || snapshot.fixedItmCeStrike() == null || ref.ceStrike() == null
                || Double.compare(snapshot.fixedItmCeStrike(), ref.ceStrike()) != 0
                || ref.ceDelta() == null || ref.ceGamma() == null || ref.ceTheta() == null) {
            return null;
        }
        double spotChange = snapshot.nifty() - ref.nifty();
        double elapsedDays = Duration.between(ref.time(), snapshot.tickTime()).toMillis() / 86_400_000.0;
        double expected = expectedPremium(ref.ce(), ref.ceDelta(), ref.ceGamma(), ref.ceTheta(), spotChange, elapsedDays);
        return snapshot.fixedItmCe() - expected;
    }

    /** Same as {@link #fixedItmCeDivergenceFrom} but for the FIXED ITM PE leg. */
    public Double fixedItmPeDivergenceFrom(FixedItmOrderReference ref, TickSnapshot snapshot) {
        if (ref == null || snapshot == null || snapshot.nifty() == null || snapshot.fixedItmPe() == null
                || snapshot.fixedItmPeStrike() == null || ref.peStrike() == null
                || Double.compare(snapshot.fixedItmPeStrike(), ref.peStrike()) != 0
                || ref.peDelta() == null || ref.peGamma() == null || ref.peTheta() == null) {
            return null;
        }
        double spotChange = snapshot.nifty() - ref.nifty();
        double elapsedDays = Duration.between(ref.time(), snapshot.tickTime()).toMillis() / 86_400_000.0;
        double expected = expectedPremium(ref.pe(), ref.peDelta(), ref.peGamma(), ref.peTheta(), spotChange, elapsedDays);
        return snapshot.fixedItmPe() - expected;
    }

    private void putSideNulls(Map<String, Object> result, String prefix, String suffix) {
        result.put(prefix + "RefStrike" + suffix, null);
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
