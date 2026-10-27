package com.example.demo.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Durably persists {@link IntervalDivergenceService}'s per-label running state (the baseline snapshot used
 * to measure the next interval, and the cumulative divergence accumulated so far today) to the {@code
 * interval_divergence_state} Postgres table, one row per (trading day, label), so that an application
 * restart mid-day restores exactly where each label left off instead of silently resetting cumulative
 * divergence to zero and re-baselining from whatever premium happens to be live at restart time.
 * <p>
 * Each label's row is upserted (not appended) every time {@link IntervalDivergenceService} updates that
 * label's baseline/cumulative total, keyed on {@code (tick_date, label)}; {@link #restoreToday()} loads
 * every row for today in one query at startup.
 */
@Service
public class IntervalDivergencePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(IntervalDivergencePersistenceService.class);

    private final JdbcTemplate jdbcTemplate;

    public IntervalDivergencePersistenceService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    void init() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS interval_divergence_state (
                    tick_date DATE NOT NULL,
                    label VARCHAR(32) NOT NULL,
                    premium DOUBLE PRECISION NOT NULL,
                    spot DOUBLE PRECISION NOT NULL,
                    strike DOUBLE PRECISION NOT NULL,
                    snapshot_time TIME NOT NULL,
                    cumulative_divergence DOUBLE PRECISION NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT now(),
                    PRIMARY KEY (tick_date, label)
                )
                """);
    }

    /** This label's persisted baseline snapshot (premium/spot/strike/time) plus its cumulative divergence
     * total, as of the last time it was persisted today. */
    public record PersistedLabelState(double premium, double spot, double strike, LocalTime time,
                                       double cumulativeDivergence) {
    }

    /** Loads every label's persisted state for today, keyed by label (e.g. {@code "ATM CE"}). Returns an
     * empty map (rather than throwing) on any failure, or if today has no persisted rows yet (e.g. the
     * very first run of a fresh trading day), so the caller simply falls back to a cold start. */
    public Map<String, PersistedLabelState> restoreToday() {
        try {
            List<Map.Entry<String, PersistedLabelState>> rows = jdbcTemplate.query(
                    "SELECT * FROM interval_divergence_state WHERE tick_date = ?",
                    this::mapRow, LocalDate.now());
            Map<String, PersistedLabelState> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, PersistedLabelState> row : rows) {
                result.put(row.getKey(), row.getValue());
            }
            return result;
        } catch (Exception ex) {
            log.warn("Failed to restore today's interval divergence state; all labels will cold-start "
                    + "(baseline resynced on next tick, cumulative divergence beginning from 0)", ex);
            return Map.of();
        }
    }

    /** Upserts the given label's current baseline snapshot and cumulative divergence total for today. */
    public void persist(String label, double premium, double spot, double strike, LocalTime time,
                         double cumulativeDivergence) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO interval_divergence_state (
                        tick_date, label, premium, spot, strike, snapshot_time, cumulative_divergence, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (tick_date, label) DO UPDATE SET
                        premium = EXCLUDED.premium,
                        spot = EXCLUDED.spot,
                        strike = EXCLUDED.strike,
                        snapshot_time = EXCLUDED.snapshot_time,
                        cumulative_divergence = EXCLUDED.cumulative_divergence,
                        updated_at = now()
                    """,
                    LocalDate.now(), label, premium, spot, strike, Time.valueOf(time), cumulativeDivergence);
        } catch (Exception ex) {
            log.warn("Failed to persist interval divergence state for label={}; a restart before the next "
                    + "successful persist would lose this label's baseline/cumulative progress", label, ex);
        }
    }

    private Map.Entry<String, PersistedLabelState> mapRow(ResultSet rs, int rowNum) throws SQLException {
        return Map.entry(rs.getString("label"), new PersistedLabelState(
                rs.getDouble("premium"), rs.getDouble("spot"), rs.getDouble("strike"),
                rs.getTime("snapshot_time").toLocalTime(), rs.getDouble("cumulative_divergence")));
    }
}
