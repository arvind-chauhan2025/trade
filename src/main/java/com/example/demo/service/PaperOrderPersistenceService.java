package com.example.demo.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable JDBC persistence for simulated (paper) CE/PE trades opened/closed by {@link PaperTradingService}.
 * Stores the full entry snapshot plus the order-anchored {@link PremiumReferenceService.FixedItmOrderReference}
 * (so a restart can resume tracking a still-open trade's exit divergence against the exact same anchor it
 * was entered against, independent of the shared 30-minute rolling reference), the real divergence-based
 * exit, and the hypothetical (analysis-only) trailing-SL outcome side by side for later P&L comparison.
 * <b>No row here ever represents a real Angel One order</b> — this is purely a backtesting ledger.
 */
@Service
public class PaperOrderPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PaperOrderPersistenceService.class);

    private final JdbcTemplate jdbcTemplate;

    public PaperOrderPersistenceService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS paper_order (
                    id BIGSERIAL PRIMARY KEY,
                    trade_date DATE NOT NULL,
                    direction VARCHAR(2) NOT NULL,
                    status VARCHAR(10) NOT NULL,
                    entry_time VARCHAR(18) NOT NULL,
                    entry_nifty DOUBLE PRECISION NOT NULL,
                    entry_premium DOUBLE PRECISION NOT NULL,
                    entry_strike DOUBLE PRECISION,
                    ref_time VARCHAR(18), ref_nifty DOUBLE PRECISION,
                    ref_ce DOUBLE PRECISION, ref_ce_strike DOUBLE PRECISION, ref_ce_delta DOUBLE PRECISION, ref_ce_gamma DOUBLE PRECISION, ref_ce_theta DOUBLE PRECISION,
                    ref_pe DOUBLE PRECISION, ref_pe_strike DOUBLE PRECISION, ref_pe_delta DOUBLE PRECISION, ref_pe_gamma DOUBLE PRECISION, ref_pe_theta DOUBLE PRECISION,
                    highest_premium DOUBLE PRECISION,
                    hypothetical_sl_hit BOOLEAN NOT NULL DEFAULT FALSE,
                    hypothetical_sl_exit_price DOUBLE PRECISION,
                    hypothetical_sl_exit_time VARCHAR(18),
                    hypothetical_sl_pnl DOUBLE PRECISION,
                    exit_time VARCHAR(18),
                    exit_nifty DOUBLE PRECISION,
                    exit_premium DOUBLE PRECISION,
                    exit_reason VARCHAR(64),
                    pnl DOUBLE PRECISION,
                    created_at TIMESTAMP NOT NULL DEFAULT now()
                )
                """);
    }

    /** Inserts a newly opened paper trade and returns its generated id. */
    public long insertOpen(PaperOrderState order) {
        PremiumReferenceService.FixedItmOrderReference ref = order.reference;
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO paper_order (
                    trade_date, direction, status, entry_time, entry_nifty, entry_premium, entry_strike,
                    ref_time, ref_nifty, ref_ce, ref_ce_strike, ref_ce_delta, ref_ce_gamma, ref_ce_theta,
                    ref_pe, ref_pe_strike, ref_pe_delta, ref_pe_gamma, ref_pe_theta, highest_premium
                ) VALUES (?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                order.tradeDate, order.direction, order.entryTime.toString(), order.entryNifty, order.entryPremium, order.entryStrike,
                ref.time().toString(), ref.nifty(), ref.ce(), ref.ceStrike(), ref.ceDelta(), ref.ceGamma(), ref.ceTheta(),
                ref.pe(), ref.peStrike(), ref.peDelta(), ref.peGamma(), ref.peTheta(), order.highestPremium);
        log.info("Paper trade OPENED id={} direction={} entryTime={} entryPremium={}", id, order.direction, order.entryTime, order.entryPremium);
        return id;
    }

    /** Updates the tracked highest premium since entry (used to compute the hypothetical trailing SL). */
    public void updateHighestPremium(long id, double highestPremium) {
        jdbcTemplate.update("UPDATE paper_order SET highest_premium = ? WHERE id = ?", highestPremium, id);
    }

    /** Records that the hypothetical (analysis-only) trailing SL was hit. Never changes {@code status} —
     * the real position stays open until a real divergence-based exit. */
    public void recordHypotheticalSlHit(long id, double highestPremium, double exitPrice, LocalTime exitTime, double pnl) {
        jdbcTemplate.update("""
                UPDATE paper_order SET highest_premium = ?, hypothetical_sl_hit = TRUE,
                    hypothetical_sl_exit_price = ?, hypothetical_sl_exit_time = ?, hypothetical_sl_pnl = ?
                WHERE id = ?
                """, highestPremium, exitPrice, exitTime.toString(), pnl, id);
        log.info("Paper trade id={} hypothetical SL hit (analysis-only): exitPrice={} pnl={}", id, exitPrice, pnl);
    }

    /** Closes a paper trade with the real, divergence-based exit. */
    public void closeOrder(long id, LocalTime exitTime, double exitNifty, double exitPremium, String exitReason, double pnl) {
        jdbcTemplate.update("""
                UPDATE paper_order SET status = 'CLOSED', exit_time = ?, exit_nifty = ?, exit_premium = ?,
                    exit_reason = ?, pnl = ? WHERE id = ?
                """, exitTime.toString(), exitNifty, exitPremium, exitReason, pnl, id);
        log.info("Paper trade CLOSED id={} exitTime={} exitPremium={} reason={} pnl={}", id, exitTime, exitPremium, exitReason, pnl);
    }

    /** Returns the currently open paper trade (if any), fully reconstructed including its anchored
     * {@link PremiumReferenceService.FixedItmOrderReference}, so a restart can resume exit tracking
     * without losing the entry-time anchor. */
    public Optional<PaperOrderState> getOpenOrder() {
        List<PaperOrderState> rows = jdbcTemplate.query(
                "SELECT * FROM paper_order WHERE status = 'OPEN' ORDER BY id DESC LIMIT 1",
                this::mapRow);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        PaperOrderState order = rows.get(0);
        order.id = jdbcTemplate.queryForObject(
                "SELECT id FROM paper_order WHERE status = 'OPEN' ORDER BY id DESC LIMIT 1", Long.class);
        return Optional.of(order);
    }

    /** Returns every paper trade (open or closed) between {@code fromDate} and {@code toDate} inclusive,
     * ordered chronologically, as plain column maps for read-only analysis/reporting. */
    public List<Map<String, Object>> getOrders(LocalDate fromDate, LocalDate toDate) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM paper_order WHERE trade_date BETWEEN ? AND ? ORDER BY id",
                fromDate, toDate);
    }

    private PaperOrderState mapRow(ResultSet rs, int rowNum) throws SQLException {
        PremiumReferenceService.FixedItmOrderReference ref = new PremiumReferenceService.FixedItmOrderReference(
                LocalTime.parse(rs.getString("ref_time")), rs.getDouble("ref_nifty"),
                rs.getDouble("ref_ce"), nullableDouble(rs, "ref_ce_strike"), nullableDouble(rs, "ref_ce_delta"), nullableDouble(rs, "ref_ce_gamma"), nullableDouble(rs, "ref_ce_theta"),
                rs.getDouble("ref_pe"), nullableDouble(rs, "ref_pe_strike"), nullableDouble(rs, "ref_pe_delta"), nullableDouble(rs, "ref_pe_gamma"), nullableDouble(rs, "ref_pe_theta"));
        PaperOrderState order = new PaperOrderState(
                rs.getDate("trade_date").toLocalDate(), rs.getString("direction"),
                LocalTime.parse(rs.getString("entry_time")), rs.getDouble("entry_nifty"),
                rs.getDouble("entry_premium"), nullableDouble(rs, "entry_strike"), ref);
        order.highestPremium = rs.getDouble("highest_premium");
        order.hypotheticalSlHit = rs.getBoolean("hypothetical_sl_hit");
        order.hypotheticalSlExitPrice = nullableDouble(rs, "hypothetical_sl_exit_price");
        String slExitTime = rs.getString("hypothetical_sl_exit_time");
        order.hypotheticalSlExitTime = slExitTime != null ? LocalTime.parse(slExitTime) : null;
        order.hypotheticalSlPnl = nullableDouble(rs, "hypothetical_sl_pnl");
        return order;
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
