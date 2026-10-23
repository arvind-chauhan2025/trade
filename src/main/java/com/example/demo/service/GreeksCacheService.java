package com.example.demo.service;

import com.example.demo.config.MockGreeksProperties;
import com.example.demo.dto.GreekHistoryEntry;
import com.example.demo.dto.OptionContract;
import com.example.demo.dto.OptionGreek;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches Option Greeks for the tracked expiry and caches the full Greek set (Delta, Gamma, Theta, ...)
 * for each of our tracked contracts (ATM CE, ATM PE, FIXED ITM CE, FIXED ITM PE), keyed by the same
 * labels used elsewhere in the pipeline. This makes Delta/Gamma/Theta available to the existing
 * in-memory tick calculations without requiring per-tick Greeks calls (Angel One's Greeks API is a
 * REST snapshot, not a streamed value).
 * <p>
 * When {@link MockGreeksProperties#isEnabled()} is {@code true} (e.g. {@code greeks.mock.enabled=true}),
 * Greeks are synthesized locally from the configured mock values instead of being fetched from Angel
 * One, so the pipeline can be exercised outside market hours when the real Greeks API has no data.
 */
@Service
public class GreeksCacheService {

    private static final Logger log = LoggerFactory.getLogger(GreeksCacheService.class);

    private final AngelOneOptionGreeksService greeksService;
    private final MockGreeksProperties mockGreeksProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, OptionGreek> greeksByLabel = new ConcurrentHashMap<>();

    public GreeksCacheService(AngelOneOptionGreeksService greeksService, MockGreeksProperties mockGreeksProperties,
                               DataSource dataSource) {
        this.greeksService = greeksService;
        this.mockGreeksProperties = mockGreeksProperties;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** Creates the {@code option_greeks} history table if needed. Every refresh (real or mock) appends a
     * new row per label instead of overwriting the previous one, so the full history of how a label's
     * Greeks evolved through the day is preserved for later analysis. */
    @PostConstruct
    void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS option_greeks (
                    tick_date DATE NOT NULL,
                    tick_time VARCHAR(16) NOT NULL,
                    label VARCHAR(32) NOT NULL,
                    captured_at TIMESTAMP NOT NULL DEFAULT now(),
                    strike DOUBLE PRECISION NOT NULL,
                    option_type VARCHAR(4) NOT NULL,
                    delta DOUBLE PRECISION NOT NULL,
                    gamma DOUBLE PRECISION NOT NULL,
                    theta DOUBLE PRECISION NOT NULL,
                    vega DOUBLE PRECISION NOT NULL,
                    implied_volatility DOUBLE PRECISION NOT NULL
                )
                """);
    }

    /**
     * Updates the cached Greek set for each labeled contract for {@code expiry}. If
     * {@code greeks.mock.enabled=true}, synthesizes Greeks locally from the configured mock values;
     * otherwise fetches real Greeks from Angel One and matches by exact strike + option type.
     * {@code tickTime} is accepted from the caller (rather than each persisted row stamping its own time)
     * so every label's Greek from this single refresh is persisted with the exact same tick time.
     */
    public void refresh(LocalDate expiry, Map<String, OptionContract> trackedContracts, String tickTime) {
        if (mockGreeksProperties.isEnabled()) {
            refreshWithMockData(expiry, trackedContracts, tickTime);
            return;
        }

        log.info("Refreshing Greeks for expiry={}", expiry);
        List<OptionGreek> greeks = greeksService.getGreeks(expiry);

        trackedContracts.forEach((label, contract) -> greeks.stream()
                .filter(g -> g.optionType().equalsIgnoreCase(contract.optionType()) && g.strike() == contract.strike())
                .findFirst()
                .ifPresentOrElse(
                        g -> {
                            greeksByLabel.put(label, g);
                            persistGreek(label, g, tickTime);
                            log.info("{} Delta={} Gamma={} Theta={} (strike={}, expiry={})",
                                    label, g.delta(), g.gamma(), g.theta(), contract.strike(), expiry);
                        },
                        () -> log.warn("No Greeks entry found for {} strike={} expiry={}", label, contract.strike(), expiry)
                ));
    }

    /** Synthesizes an {@link OptionGreek} per tracked label from {@link MockGreeksProperties}, using each
     * contract's real strike/optionType but configured mock Delta/Gamma/Theta/Vega/IV. Delta's sign
     * follows the option type (positive for CE, negative for PE), matching real-world convention. */
    private void refreshWithMockData(LocalDate expiry, Map<String, OptionContract> trackedContracts, String tickTime) {
        log.info("greeks.mock.enabled=true; synthesizing mock Greeks for expiry={} instead of calling Angel One", expiry);
        trackedContracts.forEach((label, contract) -> {
            boolean isCall = contract.optionType().equalsIgnoreCase("CE");
            double delta = isCall ? Math.abs(mockGreeksProperties.getDelta()) : -Math.abs(mockGreeksProperties.getDelta());
            OptionGreek mock = new OptionGreek(
                    contract.strike(), contract.optionType(),
                    delta, mockGreeksProperties.getGamma(), mockGreeksProperties.getTheta(),
                    mockGreeksProperties.getVega(), mockGreeksProperties.getImpliedVolatility());
            greeksByLabel.put(label, mock);
            persistGreek(label, mock, tickTime);
            log.info("{} (mock) Delta={} Gamma={} Theta={} (strike={}, expiry={})",
                    label, mock.delta(), mock.gamma(), mock.theta(), contract.strike(), expiry);
        });
    }

    /** Appends one history row for {@code label}'s just-refreshed {@link OptionGreek} into the
     * {@code option_greeks} table, stamped with {@code tickTime} (shared across every label persisted in
     * the same {@link #refresh} call). Persistence failures are logged and swallowed so a DB hiccup never
     * breaks the in-memory cache refresh that callers depend on. */
    private void persistGreek(String label, OptionGreek greek, String tickTime) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO option_greeks (
                        tick_date, tick_time, label, strike, option_type, delta, gamma, theta, vega, implied_volatility
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    LocalDate.now(), tickTime, label, greek.strike(), greek.optionType(),
                    greek.delta(), greek.gamma(), greek.theta(), greek.vega(), greek.impliedVolatility());
        } catch (Exception ex) {
            log.warn("Failed to persist Greeks history for {}", label, ex);
        }
    }

    private static final RowMapper<GreekHistoryEntry> GREEK_HISTORY_ROW_MAPPER = (rs, rowNum) -> new GreekHistoryEntry(
            rs.getObject("tick_date", LocalDate.class),
            rs.getString("tick_time"),
            rs.getString("label"),
            rs.getObject("captured_at", LocalDateTime.class),
            new OptionGreek(
                    rs.getDouble("strike"), rs.getString("option_type"),
                    rs.getDouble("delta"), rs.getDouble("gamma"), rs.getDouble("theta"),
                    rs.getDouble("vega"), rs.getDouble("implied_volatility"))
    );

    /** Returns every persisted {@link GreekHistoryEntry} for {@code label} between {@code fromDate} and
     * {@code toDate} (inclusive), ordered chronologically by capture time. Use this to inspect how a
     * tracked label's Greeks evolved over one or more trading days. */
    public List<GreekHistoryEntry> getHistory(String label, LocalDate fromDate, LocalDate toDate) {
        return jdbcTemplate.query("""
                        SELECT tick_date, tick_time, label, captured_at, strike, option_type, delta, gamma, theta, vega, implied_volatility
                        FROM option_greeks WHERE label = ? AND tick_date BETWEEN ? AND ? ORDER BY captured_at
                        """,
                GREEK_HISTORY_ROW_MAPPER, label, fromDate, toDate);
    }

    /** Returns the cached Delta for a label (ATM CE, ATM PE, FIXED ITM CE, FIXED ITM PE), or null if unavailable.
     * <p>
     * <b>Does not validate strike.</b> Between an ATM/ITM re-selection and the next successful Greeks
     * refresh for the new strike (e.g. the refresh runs every 30s, or Angel One's Greeks API briefly has
     * no entry for the new strike), this label's cached {@link OptionGreek} can still belong to the
     * <i>previous</i> strike. Callers that also know the strike the Greek should belong to (the live
     * tick/snapshot's actual strike) should prefer {@link #getDelta(String, double)}, which returns
     * {@code null} instead of silently handing back a different strike's Delta. */
    public Double getDelta(String label) {
        OptionGreek greek = greeksByLabel.get(label);
        return greek != null ? greek.delta() : null;
    }

    /** Returns the cached Gamma for a label, or null if unavailable. See {@link #getDelta(String)} for the
     * staleness caveat; prefer {@link #getGamma(String, double)} when the expected strike is known. */
    public Double getGamma(String label) {
        OptionGreek greek = greeksByLabel.get(label);
        return greek != null ? greek.gamma() : null;
    }

    /** Returns the cached Theta for a label (per day, sign as returned by the broker), or null if unavailable.
     * See {@link #getDelta(String)} for the staleness caveat; prefer {@link #getTheta(String, double)}
     * when the expected strike is known. */
    public Double getTheta(String label) {
        OptionGreek greek = greeksByLabel.get(label);
        return greek != null ? greek.theta() : null;
    }

    /** Returns the full cached {@link OptionGreek} for a label, or null if unavailable. See
     * {@link #getDelta(String)} for the staleness caveat; prefer {@link #getGreek(String, double)} when
     * the expected strike is known. */
    public OptionGreek getGreek(String label) {
        return greeksByLabel.get(label);
    }

    /** Strike-validated variant of {@link #getGreek(String)}: returns the cached {@link OptionGreek} for
     * {@code label} only if it was fetched for exactly {@code expectedStrike}. Returns {@code null} (and
     * logs a warning) if the cache is empty for this label, or still holds a different strike's Greek —
     * e.g. right after an ATM/ITM re-selection swapped strikes but the periodic Greeks refresh hasn't
     * caught up yet. This prevents attaching a stale, wrong-strike Delta/Gamma/Theta to a tick or premium
     * reference for the new strike. */
    public OptionGreek getGreek(String label, double expectedStrike) {
        OptionGreek greek = greeksByLabel.get(label);
        if (greek == null) {
            return null;
        }
        if (Double.compare(greek.strike(), expectedStrike) != 0) {
            log.warn("Stale Greeks for {}: cached strike={} but expected strike={}; suppressing to avoid "
                    + "mismatched Delta/Gamma/Theta", label, greek.strike(), expectedStrike);
            return null;
        }
        return greek;
    }

    /** Strike-validated variant of {@link #getDelta(String)}; see {@link #getGreek(String, double)}. */
    public Double getDelta(String label, double expectedStrike) {
        OptionGreek greek = getGreek(label, expectedStrike);
        return greek != null ? greek.delta() : null;
    }

    /** Strike-validated variant of {@link #getGamma(String)}; see {@link #getGreek(String, double)}. */
    public Double getGamma(String label, double expectedStrike) {
        OptionGreek greek = getGreek(label, expectedStrike);
        return greek != null ? greek.gamma() : null;
    }

    /** Strike-validated variant of {@link #getTheta(String)}; see {@link #getGreek(String, double)}. */
    public Double getTheta(String label, double expectedStrike) {
        OptionGreek greek = getGreek(label, expectedStrike);
        return greek != null ? greek.theta() : null;
    }

    /** Returns an immutable snapshot of all cached Deltas. */
    public Map<String, Double> getAllDeltas() {
        Map<String, Double> deltas = new java.util.HashMap<>();
        greeksByLabel.forEach((label, greek) -> deltas.put(label, greek.delta()));
        return Map.copyOf(deltas);
    }
}

