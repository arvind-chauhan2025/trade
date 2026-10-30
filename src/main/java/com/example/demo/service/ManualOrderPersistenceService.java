package com.example.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Durable JDBC persistence for manually placed CE/PE buy orders opened/closed via
 * {@link ManualOrderService} (the {@code /api/manual-orders} endpoints). Unlike {@code paper_order}
 * (an automated backtesting ledger), each row here represents a user-initiated "place order"/"exit
 * order" action. <b>Still never sends anything to Angel One</b> — this only records the user's intent
 * and tracks its P&amp;L against the live premium feed.
 */
@Service
public class ManualOrderPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ManualOrderPersistenceService.class);

    private final JdbcTemplate jdbcTemplate;

    public ManualOrderPersistenceService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS manual_order (
                    id BIGSERIAL PRIMARY KEY,
                    trade_date DATE NOT NULL,
                    label VARCHAR(16) NOT NULL,
                    moneyness VARCHAR(4) NOT NULL,
                    option_type VARCHAR(4) NOT NULL,
                    lots INTEGER NOT NULL,
                    lot_size INTEGER NOT NULL,
                    quantity INTEGER NOT NULL,
                    strike DOUBLE PRECISION NOT NULL,
                    status VARCHAR(10) NOT NULL,
                    entry_time VARCHAR(18) NOT NULL,
                    entry_premium DOUBLE PRECISION NOT NULL,
                    entry_value DOUBLE PRECISION NOT NULL,
                    entry_spot DOUBLE PRECISION,
                    exit_time VARCHAR(18),
                    exit_premium DOUBLE PRECISION,
                    exit_value DOUBLE PRECISION,
                    exit_spot DOUBLE PRECISION,
                    pnl DOUBLE PRECISION,
                    created_at TIMESTAMP NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("ALTER TABLE manual_order ADD COLUMN IF NOT EXISTS entry_spot DOUBLE PRECISION");
        jdbcTemplate.execute("ALTER TABLE manual_order ADD COLUMN IF NOT EXISTS exit_spot DOUBLE PRECISION");
    }

    /** Inserts a newly opened manual order and returns its generated id. {@code entrySpot} is the live
     * NIFTY index price at the moment of entry, saved purely for reference alongside the option premium. */
    public long insertOpen(String label, String moneyness, String optionType, int lots, int lotSize,
                            double strike, LocalTime entryTime, double entryPremium, double entryValue,
                            Double entrySpot) {
        int quantity = lots * lotSize;
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO manual_order (
                    trade_date, label, moneyness, option_type, lots, lot_size, quantity, strike, status,
                    entry_time, entry_premium, entry_value, entry_spot
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                LocalDate.now(), label, moneyness, optionType, lots, lotSize, quantity, strike,
                entryTime.toString(), entryPremium, entryValue, entrySpot);
        log.info("Manual order OPENED id={} label={} lots={} quantity={} strike={} entryPremium={} entryValue={} entrySpot={}",
                id, label, lots, quantity, strike, entryPremium, entryValue, entrySpot);
        return id;
    }

    /** Closes a manual order with the exit premium/value, the live NIFTY index price at exit
     * ({@code exitSpot}), and computed P&amp;L (rounded to 2 decimal places). */
    public void closeOrder(long id, LocalTime exitTime, double exitPremium, double exitValue, Double exitSpot, double pnl) {
        jdbcTemplate.update("""
                UPDATE manual_order SET status = 'CLOSED', exit_time = ?, exit_premium = ?, exit_value = ?,
                    exit_spot = ?, pnl = ?
                WHERE id = ?
                """, exitTime.toString(), exitPremium, exitValue, exitSpot, pnl, id);
        log.info("Manual order CLOSED id={} exitTime={} exitPremium={} exitValue={} exitSpot={} pnl={}",
                id, exitTime, exitPremium, exitValue, exitSpot, pnl);
    }

    /** Returns the currently open manual order (if any) — only one may be open at a time. */
    public Optional<ManualOrderSummary> getOpenOrder() {
        List<ManualOrderSummary> rows = jdbcTemplate.query(
                "SELECT * FROM manual_order WHERE status = 'OPEN' ORDER BY id DESC LIMIT 1", this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Returns one manual order (open or closed) by id, or empty if it doesn't exist. */
    public Optional<ManualOrderSummary> getById(long id) {
        List<ManualOrderSummary> rows = jdbcTemplate.query(
                "SELECT * FROM manual_order WHERE id = ?", this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Returns every manual order (open or closed) between {@code fromDate} and {@code toDate} inclusive,
     * ordered chronologically. */
    public List<ManualOrderSummary> getOrders(LocalDate fromDate, LocalDate toDate) {
        return jdbcTemplate.query(
                "SELECT * FROM manual_order WHERE trade_date BETWEEN ? AND ? ORDER BY id",
                this::mapRow, fromDate, toDate);
    }

    /** Read-only snapshot of one manual order row (open or closed). Mirrors the {@code manual_order}
     * table columns 1:1 as a fixed, typed shape. */
    public record ManualOrderSummary(
            long id, LocalDate tradeDate, String label, String moneyness, String optionType,
            int lots, int lotSize, int quantity, double strike, String status,
            LocalTime entryTime, double entryPremium, double entryValue, Double entrySpot,
            LocalTime exitTime, Double exitPremium, Double exitValue, Double exitSpot, Double pnl,
            LocalDateTime createdAt) {
    }

    private ManualOrderSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
        String exitTime = rs.getString("exit_time");
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ManualOrderSummary(
                rs.getLong("id"), rs.getDate("trade_date").toLocalDate(), rs.getString("label"),
                rs.getString("moneyness"), rs.getString("option_type"),
                rs.getInt("lots"), rs.getInt("lot_size"), rs.getInt("quantity"), rs.getDouble("strike"),
                rs.getString("status"), LocalTime.parse(rs.getString("entry_time")),
                rs.getDouble("entry_premium"), rs.getDouble("entry_value"), nullableDouble(rs, "entry_spot"),
                exitTime != null ? LocalTime.parse(exitTime) : null,
                nullableDouble(rs, "exit_premium"), nullableDouble(rs, "exit_value"), nullableDouble(rs, "exit_spot"),
                nullableDouble(rs, "pnl"),
                createdAt != null ? createdAt.toLocalDateTime() : null);
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
