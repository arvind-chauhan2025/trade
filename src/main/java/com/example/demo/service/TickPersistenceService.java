package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.TickRecord;
import com.example.demo.dto.TickSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stores every tick in a single {@code Map<LocalTime, TickSnapshot>}, keyed
 * by the exchange tick time. Each snapshot combines the NIFTY index price
 * (NSE_CM) together with the four option prices (NSE_FO) that occurred at
 * that same second — whichever field a tick belongs to is simply updated in
 * place; there is no separate override logic needed.
 * <p>
 * Every update is also asynchronously upserted into a single H2 table via a
 * background-drained queue, so the WebSocket thread is never blocked by DB
 * I/O. On shutdown, the full table is exported to a JSON file. This class is
 * a stand-in for a future proper database/repository layer.
 */
@Service
public class TickPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TickPersistenceService.class);
    private static final DateTimeFormatter TICK_TIME_PARSER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AngelOneProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final GreeksCacheService greeksCacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Single map: tick time -> combined snapshot of NIFTY + all 4 option prices at that time. */
    private final ConcurrentSkipListMap<LocalTime, TickSnapshot> snapshotsByTime = new ConcurrentSkipListMap<>();
    private final BlockingQueue<TickRecord> queue = new LinkedBlockingQueue<>(10_000);
    private final ScheduledExecutorService exportExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tick-export-scheduler");
        t.setDaemon(true);
        return t;
    });

    private volatile Thread worker;
    private volatile boolean running;

    public TickPersistenceService(AngelOneProperties properties, DataSource dataSource, GreeksCacheService greeksCacheService) {
        this.properties = properties;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.greeksCacheService = greeksCacheService;
    }

    @PostConstruct
    void start() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tick_snapshot (
                    tick_time VARCHAR(16) PRIMARY KEY,
                    nifty DOUBLE,
                    atm_ce DOUBLE,
                    atm_ce_delta DOUBLE,
                    atm_pe DOUBLE,
                    atm_pe_delta DOUBLE,
                    fixed_itm_ce DOUBLE,
                    fixed_itm_ce_delta DOUBLE,
                    fixed_itm_pe DOUBLE,
                    fixed_itm_pe_delta DOUBLE
                )
                """);

        running = true;
        worker = new Thread(this::drainLoop, "tick-persistence-writer");
        worker.setDaemon(true);
        worker.start();

        // Safety net: if the process is killed forcibly (e.g. taskkill, or Ctrl+C not
        // reaching the JVM cleanly on Windows), @PreDestroy may never run. Periodically
        // export so at most a few seconds of data are ever at risk of being lost.
        exportExecutor.scheduleAtFixedRate(this::exportToJson, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        running = false;
        exportExecutor.shutdownNow();
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        // Drain any ticks that arrived but hadn't been persisted yet, so the final
        // export below reflects every tick received before shutdown.
        drainRemainingSynchronously();
        exportToJson();
    }

    private void drainRemainingSynchronously() {
        TickRecord tick;
        while ((tick = queue.poll()) != null) {
            try {
                persist(tick);
            } catch (Exception ex) {
                log.warn("Failed to persist a tick during shutdown drain", ex);
            }
        }
    }

    /** Merges this tick's price (plus cached Delta, for option labels) into the snapshot for its tick time,
     * and enqueues it for H2 persistence. */
    public void offer(TickRecord tick) {
        LocalTime time = LocalTime.parse(tick.tickTime(), TICK_TIME_PARSER);
        Double delta = greeksCacheService.getDelta(tick.label());
        snapshotsByTime.compute(time, (t, existing) ->
                (existing != null ? existing : TickSnapshot.empty(t)).with(tick.label(), tick.price(), delta));
        if (!queue.offer(tick)) {
            log.warn("Tick persistence queue is full; dropping tick for {}", tick.label());
        }
    }

    /** Returns an immutable, chronologically-ordered snapshot of the whole map. */
    public Map<LocalTime, TickSnapshot> getSnapshots() {
        return new TreeMap<>(snapshotsByTime);
    }

    private void drainLoop() {
        while (running) {
            try {
                TickRecord tick = queue.take();
                persist(tick);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.warn("Failed to persist a tick to H2", ex);
            }
        }
    }

    private void persist(TickRecord tick) {
        String column = switch (tick.label()) {
            case "NIFTY" -> "nifty";
            case "ATM CE" -> "atm_ce";
            case "ATM PE" -> "atm_pe";
            case "FIXED ITM CE" -> "fixed_itm_ce";
            case "FIXED ITM PE" -> "fixed_itm_pe";
            default -> null;
        };
        if (column == null) {
            return;
        }
        // Single-consumer thread: safe to upsert-then-update-one-column without extra locking.
        jdbcTemplate.update("MERGE INTO tick_snapshot (tick_time) KEY (tick_time) VALUES (?)", tick.tickTime());
        jdbcTemplate.update("UPDATE tick_snapshot SET " + column + " = ? WHERE tick_time = ?",
                tick.price(), tick.tickTime());

        String deltaColumn = switch (tick.label()) {
            case "ATM CE" -> "atm_ce_delta";
            case "ATM PE" -> "atm_pe_delta";
            case "FIXED ITM CE" -> "fixed_itm_ce_delta";
            case "FIXED ITM PE" -> "fixed_itm_pe_delta";
            default -> null;
        };
        if (deltaColumn != null) {
            Double delta = greeksCacheService.getDelta(tick.label());
            if (delta != null) {
                jdbcTemplate.update("UPDATE tick_snapshot SET " + deltaColumn + " = ? WHERE tick_time = ?",
                        delta, tick.tickTime());
            }
        }
    }

    /** Exports the full tick_snapshot table to a JSON file. */
    private void exportToJson() {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            jdbcTemplate.query("SELECT * FROM tick_snapshot ORDER BY tick_time", rs -> {
                var meta = rs.getMetaData();
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            });

            Path path = Path.of(properties.getTickExportPath()).toAbsolutePath();
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), "ticks-export-", ".tmp");
            try {
                Files.writeString(tmp, objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(Map.of("tickSnapshots", rows)));
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            log.info("Exported {} tick snapshot rows to {}", rows.size(), path);
        } catch (Exception ex) {
            log.error("Failed to export H2 tick data to JSON on shutdown", ex);
        }
    }
}



