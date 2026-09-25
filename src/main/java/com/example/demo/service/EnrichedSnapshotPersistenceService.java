package com.example.demo.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists every enriched snapshot map produced by {@link PremiumReferenceService#enrich} into the
 * {@code enriched_snapshot} table. The table's columns are derived directly from the map's keys
 * (camelCase keys converted to snake_case column names) rather than a hand-maintained schema, so any field
 * added to {@link PremiumReferenceService#enrich} is picked up automatically as a new {@code DOUBLE
 * PRECISION} column (added on demand via {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}) without
 * touching this class. The {@code tickTime} entry is used (together with today's date) as the row's
 * primary key instead of being stored as a regular column. Writes happen synchronously on the caller's
 * thread (the broadcast scheduler, every 5s) since there's no meaningful write volume to warrant a
 * background queue/worker.
 */
@Service
public class EnrichedSnapshotPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(EnrichedSnapshotPersistenceService.class);
    private static final String TABLE = "enriched_snapshot";

    private final JdbcTemplate jdbcTemplate;
    /** Columns already confirmed to exist on {@link #TABLE}, so we only issue {@code ALTER TABLE} once per column. */
    private final Set<String> knownColumns = ConcurrentHashMap.newKeySet();

    public EnrichedSnapshotPersistenceService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    void start() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS enriched_snapshot (
                    tick_date DATE NOT NULL,
                    tick_time VARCHAR(18) NOT NULL,
                    PRIMARY KEY (tick_date, tick_time)
                )
                """);
        // Safety net for a pre-existing table created before the column was widened for HH:mm:ss.SSSSSS values.
        jdbcTemplate.execute("ALTER TABLE enriched_snapshot ALTER COLUMN tick_time TYPE VARCHAR(18)");
    }

    /** Upserts one enriched snapshot map (as produced by {@link PremiumReferenceService#enrich}) into
     * {@link #TABLE}, keyed by today's date + {@code tickTime}. Every other map key is converted to a
     * snake_case column name, the column is created on demand if it doesn't exist yet, and its (numeric)
     * value is written into that column. Logs and swallows any failure so a DB hiccup never breaks the
     * caller's broadcast loop. */
    public void offer(Map<String, Object> enriched) {
        try {
            persist(enriched);
        } catch (Exception ex) {
            log.warn("Failed to persist an enriched snapshot to Postgres", ex);
        }
    }

    private void persist(Map<String, Object> enriched) {
        Object tickTimeValue = enriched.get("tickTime");
        String tickTime = tickTimeValue instanceof LocalTime lt
                ? lt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
                : String.valueOf(tickTimeValue);

        Map<String, Object> columns = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : enriched.entrySet()) {
            if ("tickTime".equals(entry.getKey())) {
                continue;
            }
            columns.put(toSnakeCase(entry.getKey()), entry.getValue());
        }
        ensureColumnsExist(columns.keySet());

        StringBuilder insertCols = new StringBuilder("tick_date, tick_time");
        StringBuilder placeholders = new StringBuilder("?, ?");
        StringBuilder updateClause = new StringBuilder();
        Object[] args = new Object[2 + columns.size()];
        args[0] = LocalDate.now();
        args[1] = tickTime;

        int i = 2;
        for (Map.Entry<String, Object> column : columns.entrySet()) {
            insertCols.append(", ").append(column.getKey());
            placeholders.append(", ?");
            if (!updateClause.isEmpty()) {
                updateClause.append(", ");
            }
            updateClause.append(column.getKey()).append(" = EXCLUDED.").append(column.getKey());
            args[i] = toDouble(column.getValue());
            i++;
        }

        String sql = "INSERT INTO " + TABLE + " (" + insertCols + ") VALUES (" + placeholders + ") "
                + "ON CONFLICT (tick_date, tick_time) DO UPDATE SET " + updateClause;
        jdbcTemplate.update(sql, args);
    }

    private void ensureColumnsExist(Set<String> columns) {
        for (String column : columns) {
            if (knownColumns.add(column)) {
                jdbcTemplate.execute("ALTER TABLE " + TABLE + " ADD COLUMN IF NOT EXISTS " + column + " DOUBLE PRECISION");
            }
        }
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    /** Converts a camelCase map key (e.g. {@code atmCeExpected30m}) into a snake_case SQL column name
     * (e.g. {@code atm_ce_expected30m}). */
    private static String toSnakeCase(String camelCase) {
        return camelCase.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_").toLowerCase();
    }

    /** Returns every persisted enriched snapshot row (all columns, dynamic schema included) for any trade
     * date in {@code [fromDate, toDate]} (inclusive on both ends), ordered chronologically (date, then
     * tick time). Each row is a plain column-name -> value map, since the table's columns grow dynamically
     * as new fields are added to {@link PremiumReferenceService#enrich}. {@code tick_date} is normalized to
     * a plain {@link LocalDate} (so it serializes as {@code "2026-09-24"} rather than a timestamp), and a
     * combined {@code tickDateTime} (ISO {@code LocalDateTime}) field is added so multi-day results can be
     * plotted on a single continuous timeline (e.g. an Angular chart) without recombining date + time
     * client-side. */
    public List<Map<String, Object>> getSnapshots(LocalDate fromDate, LocalDate toDate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM " + TABLE + " WHERE tick_date BETWEEN ? AND ? ORDER BY tick_date, tick_time",
                fromDate, toDate);
        rows.forEach(this::normalizeRow);
        return rows;
    }

    /** Coerces {@code tick_date} (returned by the JDBC driver as {@code java.sql.Date}, which Jackson would
     * otherwise serialize as a timezone-shifted timestamp) into a plain {@link LocalDate}, and adds a
     * {@code tickDateTime} field combining {@code tick_date} + {@code tick_time} for easy charting. */
    private void normalizeRow(Map<String, Object> row) {
        Object rawDate = row.get("tick_date");
        LocalDate tickDate = rawDate instanceof java.sql.Date sqlDate ? sqlDate.toLocalDate()
                : rawDate instanceof LocalDate ld ? ld
                : LocalDate.parse(String.valueOf(rawDate));
        row.put("tick_date", tickDate);

        Object rawTime = row.get("tick_time");
        if (rawTime != null) {
            LocalTime tickTime = LocalTime.parse(String.valueOf(rawTime), DateTimeFormatter.ISO_LOCAL_TIME);
            row.put("tickDateTime", LocalDateTime.of(tickDate, tickTime));
        }
    }

    /** Returns every persisted enriched snapshot row whose (trade date + tick time) falls within
     * {@code [from, to]} (inclusive on both ends), ordered chronologically. The date component of the
     * bound narrows the SQL query; the exact time-of-day bound is then applied in-memory since
     * {@code tick_time} may be stored without a fixed width (e.g. "09:15:00" vs "09:15:00.500000"). */
    public List<Map<String, Object>> getSnapshots(LocalDateTime from, LocalDateTime to) {
        return getSnapshots(from.toLocalDate(), to.toLocalDate()).stream()
                .filter(row -> {
                    LocalDateTime dateTime = (LocalDateTime) row.get("tickDateTime");
                    return dateTime != null && !dateTime.isBefore(from) && !dateTime.isAfter(to);
                })
                .toList();
    }
}
