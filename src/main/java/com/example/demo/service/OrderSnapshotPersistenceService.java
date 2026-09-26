package com.example.demo.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Persists one row per 5-second snapshot for the lifetime of every paper trade opened by
 * {@link PaperTradingService} (entry tick through exit tick, inclusive), into the
 * {@code order_snapshot} table: NIFTY price, the FIXED ITM CE/PE premiums ("spread"), the existing
 * global 30-minute divergence values, the order-anchored divergence recomputed against the trade's own
 * entry-time reference, and the entry/exit reason for that tick (if any). This is the audit trail used to
 * reconstruct/analyze exactly why and when each simulated trade fired.
 */
@Service
public class OrderSnapshotPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(OrderSnapshotPersistenceService.class);

    private final JdbcTemplate jdbcTemplate;

    public OrderSnapshotPersistenceService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS order_snapshot (
                    id BIGSERIAL PRIMARY KEY,
                    order_id BIGINT NOT NULL,
                    tick_date DATE NOT NULL,
                    tick_time VARCHAR(18) NOT NULL,
                    nifty DOUBLE PRECISION,
                    fixed_itm_ce DOUBLE PRECISION,
                    fixed_itm_pe DOUBLE PRECISION,
                    fixed_itm_ce_divergence30m DOUBLE PRECISION,
                    fixed_itm_pe_divergence30m DOUBLE PRECISION,
                    anchored_ce_divergence DOUBLE PRECISION,
                    anchored_pe_divergence DOUBLE PRECISION,
                    event VARCHAR(32),
                    created_at TIMESTAMP NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS order_snapshot_order_id_idx ON order_snapshot (order_id)");
    }

    /** Inserts one tick's worth of order-scoped divergence/price data. {@code event} is one of
     * {@code "ENTRY"}, {@code "EXIT_DIVERGENCE"}, {@code "HYPOTHETICAL_SL_HIT"} or {@code null} for a
     * regular in-between tick. Logs and swallows failures so a DB hiccup never breaks the broadcast loop. */
    public void insert(long orderId, LocalDate tickDate, LocalTime tickTime, Double nifty,
                        Double fixedItmCe, Double fixedItmPe, Double ceDivergence30m, Double peDivergence30m,
                        Double anchoredCeDivergence, Double anchoredPeDivergence, String event) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO order_snapshot (
                        order_id, tick_date, tick_time, nifty, fixed_itm_ce, fixed_itm_pe,
                        fixed_itm_ce_divergence30m, fixed_itm_pe_divergence30m,
                        anchored_ce_divergence, anchored_pe_divergence, event
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    orderId, tickDate, tickTime.toString(), nifty, fixedItmCe, fixedItmPe,
                    ceDivergence30m, peDivergence30m, anchoredCeDivergence, anchoredPeDivergence, event);
        } catch (Exception ex) {
            log.warn("Failed to persist order_snapshot row for order id={}", orderId, ex);
        }
    }

    /** Returns every persisted tick for one paper trade, ordered chronologically, for later analysis. */
    public List<Map<String, Object>> getSnapshots(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM order_snapshot WHERE order_id = ? ORDER BY id", orderId);
    }
}
