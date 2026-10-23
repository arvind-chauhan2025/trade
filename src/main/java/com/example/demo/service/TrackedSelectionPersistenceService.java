package com.example.demo.service;

import com.example.demo.dto.OptionContract;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Durably persists {@code TradingApplication}'s currently tracked ATM CE/PE and FIXED ITM CE/PE
 * selection (token/symbol/strike per leg, plus the expiry and the instant the selection was made) to the
 * {@code tracked_selection} Postgres table, so that a same-day application restart can restore the exact
 * strikes already in use instead of silently re-deriving (possibly different) ones from the live spot
 * price at restart time.
 * <p>
 * Every (re)selection — the first one of the day in {@code startPipeline()}, and any later one from
 * {@code reselectAtmItmIfWindowElapsed()} that actually changes a leg's strike — appends a new row rather
 * than overwriting the previous one, mirroring {@link PremiumReferenceService}'s history-keeping
 * approach. {@link #restoreTodaysSelection()} only ever needs the single most recent row for today.
 */
@Service
public class TrackedSelectionPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TrackedSelectionPersistenceService.class);

    private final JdbcTemplate jdbcTemplate;

    public TrackedSelectionPersistenceService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tracked_selection (
                    id BIGSERIAL PRIMARY KEY,
                    tick_date DATE NOT NULL,
                    selected_at TIMESTAMP NOT NULL,
                    expiry DATE NOT NULL,
                    atm_ce_token VARCHAR(32) NOT NULL, atm_ce_symbol VARCHAR(64) NOT NULL, atm_ce_strike DOUBLE PRECISION NOT NULL,
                    atm_pe_token VARCHAR(32) NOT NULL, atm_pe_symbol VARCHAR(64) NOT NULL, atm_pe_strike DOUBLE PRECISION NOT NULL,
                    fixed_itm_ce_token VARCHAR(32) NOT NULL, fixed_itm_ce_symbol VARCHAR(64) NOT NULL, fixed_itm_ce_strike DOUBLE PRECISION NOT NULL,
                    fixed_itm_pe_token VARCHAR(32) NOT NULL, fixed_itm_pe_symbol VARCHAR(64) NOT NULL, fixed_itm_pe_strike DOUBLE PRECISION NOT NULL
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_tracked_selection_tick_date ON tracked_selection (tick_date, selected_at DESC)");
    }

    /** A persisted selection's identifying fields per leg (token/symbol/strike) - enough to resolve the
     * matching live {@link OptionContract} out of a freshly-loaded expiry contract list by token, plus
     * the original selection instant (restored into {@code lastAtmItmSelectionAt} so a restart doesn't
     * reset the rolling re-selection window). */
    public record PersistedLeg(String token, String symbol, double strike) {
    }

    public record PersistedSelection(Instant selectedAt, LocalDate expiry,
                                      PersistedLeg atmCe, PersistedLeg atmPe,
                                      PersistedLeg fixedItmCe, PersistedLeg fixedItmPe) {
    }

    /** Returns today's most recently persisted selection, if any was recorded yet (e.g. none on the very
     * first pipeline start of the day, before the first selection has been made/persisted). */
    public Optional<PersistedSelection> restoreTodaysSelection() {
        try {
            List<PersistedSelection> rows = jdbcTemplate.query(
                    """
                    SELECT * FROM tracked_selection WHERE tick_date = ? ORDER BY selected_at DESC LIMIT 1
                    """,
                    this::mapRow, LocalDate.now());
            return rows.stream().findFirst();
        } catch (Exception ex) {
            log.warn("Failed to restore today's tracked selection; a fresh selection will be computed from live spot instead", ex);
            return Optional.empty();
        }
    }

    /** Appends a new row recording the given selection as today's current tracked strikes. */
    public void persist(Instant selectedAt, LocalDate expiry,
                         OptionContract atmCe, OptionContract atmPe,
                         OptionContract fixedItmCe, OptionContract fixedItmPe) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO tracked_selection (
                        tick_date, selected_at, expiry,
                        atm_ce_token, atm_ce_symbol, atm_ce_strike,
                        atm_pe_token, atm_pe_symbol, atm_pe_strike,
                        fixed_itm_ce_token, fixed_itm_ce_symbol, fixed_itm_ce_strike,
                        fixed_itm_pe_token, fixed_itm_pe_symbol, fixed_itm_pe_strike
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    LocalDate.now(), Timestamp.from(selectedAt), expiry,
                    atmCe.token(), atmCe.symbol(), atmCe.strike(),
                    atmPe.token(), atmPe.symbol(), atmPe.strike(),
                    fixedItmCe.token(), fixedItmCe.symbol(), fixedItmCe.strike(),
                    fixedItmPe.token(), fixedItmPe.symbol(), fixedItmPe.strike());
        } catch (Exception ex) {
            log.warn("Failed to persist tracked selection; a restart before the next (re)selection would "
                    + "have to re-derive strikes from live spot instead of restoring this one", ex);
        }
    }

    private PersistedSelection mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PersistedSelection(
                rs.getTimestamp("selected_at").toInstant(),
                rs.getDate("expiry").toLocalDate(),
                new PersistedLeg(rs.getString("atm_ce_token"), rs.getString("atm_ce_symbol"), rs.getDouble("atm_ce_strike")),
                new PersistedLeg(rs.getString("atm_pe_token"), rs.getString("atm_pe_symbol"), rs.getDouble("atm_pe_strike")),
                new PersistedLeg(rs.getString("fixed_itm_ce_token"), rs.getString("fixed_itm_ce_symbol"), rs.getDouble("fixed_itm_ce_strike")),
                new PersistedLeg(rs.getString("fixed_itm_pe_token"), rs.getString("fixed_itm_pe_symbol"), rs.getDouble("fixed_itm_pe_strike")));
    }
}
