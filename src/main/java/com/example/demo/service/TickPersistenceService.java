package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.TickRecord;
import com.example.demo.dto.TickSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Stores every tick in a single {@code Map<LocalTime, TickSnapshot>}, keyed
 * by the exchange tick time. Each snapshot combines the NIFTY index price
 * (NSE_CM) together with the four option prices and the NIFTY future price
 * (NSE_FO) that occurred at that same second — whichever field a tick
 * belongs to is simply updated in place; there is no separate override logic
 * needed. Whenever a snapshot becomes fully populated (every tracked label
 * has a price), it is additionally pushed onto {@link #snapshotQueue} for
 * consumers that only care about complete snapshots.
 * <p>
 * Every update is also asynchronously persisted into Postgres via
 * background-drained queues, so the WebSocket thread is never blocked by DB
 * I/O. Both tables key on {@code tick_date} + {@code tick_time} so ticks
 * from different trading days never collide. This class is a stand-in for a
 * future proper database/repository layer.
 */
@Service
public class TickPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TickPersistenceService.class);
    private static final DateTimeFormatter TICK_TIME_PARSER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AngelOneProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final GreeksCacheService greeksCacheService;
    private final PremiumReferenceService premiumReferenceService;

    /** Single map: tick time -> combined snapshot of NIFTY + all 4 option prices at that time. */
    private final ConcurrentSkipListMap<LocalTime, TickSnapshot> snapshotsByTime = new ConcurrentSkipListMap<>();
    private final BlockingQueue<TickRecord> queue = new LinkedBlockingQueue<>(10_000);
    /** Holds only snapshots that have every tracked label populated (see {@link TickSnapshot#isComplete()}). */
    private final BlockingQueue<TickSnapshot> snapshotQueue = new LinkedBlockingQueue<>(10_000);
    /**
     * Global, process-wide reference to the most recently completed {@link TickSnapshot}
     * (i.e. the last snapshot for which {@link TickSnapshot#isComplete()} was true).
     * Any class can read the latest complete snapshot via {@link #getLatestCompleteSnapshot()}
     * without needing a reference to this service instance.
     */
    private static volatile TickSnapshot latestCompleteSnapshot;

    private volatile Thread worker;
    private volatile Thread snapshotWorker;
    private volatile boolean running;

    public TickPersistenceService(AngelOneProperties properties, DataSource dataSource, GreeksCacheService greeksCacheService,
                                   PremiumReferenceService premiumReferenceService) {
        this.properties = properties;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.greeksCacheService = greeksCacheService;
        this.premiumReferenceService = premiumReferenceService;
    }

    @PostConstruct
    void start() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ticks (
                    tick_date DATE NOT NULL,
                    tick_time VARCHAR(16) NOT NULL,
                    nifty DOUBLE PRECISION,
                    atm_ce DOUBLE PRECISION,
                    atm_ce_strike DOUBLE PRECISION,
                    atm_ce_delta DOUBLE PRECISION,
                    atm_pe DOUBLE PRECISION,
                    atm_pe_strike DOUBLE PRECISION,
                    atm_pe_delta DOUBLE PRECISION,
                    fixed_itm_ce DOUBLE PRECISION,
                    fixed_itm_ce_strike DOUBLE PRECISION,
                    fixed_itm_ce_delta DOUBLE PRECISION,
                    fixed_itm_pe DOUBLE PRECISION,
                    fixed_itm_pe_strike DOUBLE PRECISION,
                    fixed_itm_pe_delta DOUBLE PRECISION,
                    nifty_fut DOUBLE PRECISION,
                    PRIMARY KEY (tick_date, tick_time)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tick_snapshot (
                    tick_date DATE NOT NULL,
                    tick_time VARCHAR(16) NOT NULL,
                    nifty DOUBLE PRECISION,
                    atm_ce DOUBLE PRECISION,
                    atm_ce_strike DOUBLE PRECISION,
                    atm_ce_delta DOUBLE PRECISION,
                    atm_pe DOUBLE PRECISION,
                    atm_pe_strike DOUBLE PRECISION,
                    atm_pe_delta DOUBLE PRECISION,
                    fixed_itm_ce DOUBLE PRECISION,
                    fixed_itm_ce_strike DOUBLE PRECISION,
                    fixed_itm_ce_delta DOUBLE PRECISION,
                    fixed_itm_pe DOUBLE PRECISION,
                    fixed_itm_pe_strike DOUBLE PRECISION,
                    fixed_itm_pe_delta DOUBLE PRECISION,
                    nifty_fut DOUBLE PRECISION,
                    PRIMARY KEY (tick_date, tick_time)
                )
                """);
        // Safety net for pre-existing tables created before the strike columns were added.
        jdbcTemplate.execute("ALTER TABLE tick_snapshot ADD COLUMN IF NOT EXISTS atm_ce_strike DOUBLE PRECISION");
        jdbcTemplate.execute("ALTER TABLE tick_snapshot ADD COLUMN IF NOT EXISTS atm_pe_strike DOUBLE PRECISION");
        jdbcTemplate.execute("ALTER TABLE tick_snapshot ADD COLUMN IF NOT EXISTS fixed_itm_ce_strike DOUBLE PRECISION");
        jdbcTemplate.execute("ALTER TABLE tick_snapshot ADD COLUMN IF NOT EXISTS fixed_itm_pe_strike DOUBLE PRECISION");

        running = true;
        worker = new Thread(this::drainLoop, "tick-persistence-writer");
        worker.setDaemon(true);
        worker.start();

        snapshotWorker = new Thread(this::drainSnapshotLoop, "tick-snapshot-persistence-writer");
        snapshotWorker.setDaemon(true);
        snapshotWorker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (snapshotWorker != null) {
            snapshotWorker.interrupt();
            try {
                snapshotWorker.join(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        // Drain any ticks/snapshots that arrived but hadn't been persisted yet.
        drainRemainingSynchronously();
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
        TickSnapshot snapshot;
        while ((snapshot = snapshotQueue.poll()) != null) {
            try {
                persistSnapshot(snapshot);
            } catch (Exception ex) {
                log.warn("Failed to persist a complete snapshot during shutdown drain", ex);
            }
        }
    }

    /** Merges this tick's price (plus cached Delta, for option labels) into the snapshot for its tick time,
     * and enqueues it for Postgres persistence. Once the snapshot for that tick time has every tracked label
     * populated, the completed snapshot is also pushed onto {@link #snapshotQueue}. */
    public void offer(TickRecord tick) {
        LocalTime time = LocalTime.parse(tick.tickTime(), TICK_TIME_PARSER);
        Double delta = greeksCacheService.getDelta(tick.label());
        TickSnapshot updated = snapshotsByTime.compute(time, (t, existing) ->
                (existing != null ? existing : TickSnapshot.empty(t)).with(tick.label(), tick.price(), tick.strike(), delta));
        if (!queue.offer(tick)) {
            log.warn("Tick persistence queue is full; dropping tick for {}", tick.label());
        }
        if (updated.isComplete() && !snapshotQueue.offer(updated)) {
            log.warn("Complete snapshot queue is full; dropping complete snapshot for {}", tick.tickTime());
        }
        if (updated.isComplete()) {
            latestCompleteSnapshot = updated;
            premiumReferenceService.captureIfNeeded(updated);
        }
    }

    /** Returns the most recently completed {@link TickSnapshot} (all tracked labels populated), or
     * {@code null} if none has completed yet. Accessible statically so any class can read it without
     * needing this service's instance. */
    public static TickSnapshot getLatestCompleteSnapshot() {
        return latestCompleteSnapshot;
    }

    /** Returns the queue of snapshots that had every tracked label (NIFTY, ATM CE/PE, FIXED ITM CE/PE,
     * NIFTY FUT) populated at the moment they became complete. Consumers should poll/take from this queue. */
    public BlockingQueue<TickSnapshot> getSnapshotQueue() {
        return snapshotQueue;
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
                log.warn("Failed to persist a tick to Postgres", ex);
            }
        }
    }

    private void drainSnapshotLoop() {
        while (running) {
            try {
                TickSnapshot snapshot = snapshotQueue.take();
                persistSnapshot(snapshot);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.warn("Failed to persist a complete snapshot to Postgres", ex);
            }
        }
    }

    /** Upserts the raw tick into the {@code ticks} table, keyed by (tick_date, tick_time). Only the column(s)
     * matching this tick's label are written/overwritten; every other label's columns for that row are left
     * untouched (or default to NULL on first insert for that time). This gives a wide, TickSnapshot-shaped row
     * per time that fills in progressively as each label ticks, as opposed to {@code tick_snapshot} which only
     * gets a row once every label is present. */
    private void persist(TickRecord tick) {
        Double delta = greeksCacheService.getDelta(tick.label());
        LocalDate today = LocalDate.now();
        switch (tick.label()) {
            case "NIFTY" -> jdbcTemplate.update("""
                    INSERT INTO ticks (tick_date, tick_time, nifty) VALUES (?, ?, ?)
                    ON CONFLICT (tick_date, tick_time) DO UPDATE SET nifty = EXCLUDED.nifty
                    """, today, tick.tickTime(), tick.price());
            case "ATM CE" -> jdbcTemplate.update("""
                    INSERT INTO ticks (tick_date, tick_time, atm_ce, atm_ce_strike, atm_ce_delta) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (tick_date, tick_time) DO UPDATE SET
                        atm_ce = EXCLUDED.atm_ce, atm_ce_strike = EXCLUDED.atm_ce_strike, atm_ce_delta = EXCLUDED.atm_ce_delta
                    """, today, tick.tickTime(), tick.price(), tick.strike(), delta);
            case "ATM PE" -> jdbcTemplate.update("""
                    INSERT INTO ticks (tick_date, tick_time, atm_pe, atm_pe_strike, atm_pe_delta) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (tick_date, tick_time) DO UPDATE SET
                        atm_pe = EXCLUDED.atm_pe, atm_pe_strike = EXCLUDED.atm_pe_strike, atm_pe_delta = EXCLUDED.atm_pe_delta
                    """, today, tick.tickTime(), tick.price(), tick.strike(), delta);
            case "FIXED ITM CE" -> jdbcTemplate.update("""
                    INSERT INTO ticks (tick_date, tick_time, fixed_itm_ce, fixed_itm_ce_strike, fixed_itm_ce_delta) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (tick_date, tick_time) DO UPDATE SET
                        fixed_itm_ce = EXCLUDED.fixed_itm_ce, fixed_itm_ce_strike = EXCLUDED.fixed_itm_ce_strike, fixed_itm_ce_delta = EXCLUDED.fixed_itm_ce_delta
                    """, today, tick.tickTime(), tick.price(), tick.strike(), delta);
            case "FIXED ITM PE" -> jdbcTemplate.update("""
                    INSERT INTO ticks (tick_date, tick_time, fixed_itm_pe, fixed_itm_pe_strike, fixed_itm_pe_delta) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (tick_date, tick_time) DO UPDATE SET
                        fixed_itm_pe = EXCLUDED.fixed_itm_pe, fixed_itm_pe_strike = EXCLUDED.fixed_itm_pe_strike, fixed_itm_pe_delta = EXCLUDED.fixed_itm_pe_delta
                    """, today, tick.tickTime(), tick.price(), tick.strike(), delta);
            case "NIFTY FUT" -> jdbcTemplate.update("""
                    INSERT INTO ticks (tick_date, tick_time, nifty_fut) VALUES (?, ?, ?)
                    ON CONFLICT (tick_date, tick_time) DO UPDATE SET nifty_fut = EXCLUDED.nifty_fut
                    """, today, tick.tickTime(), tick.price());
            default -> log.warn("Unrecognized tick label '{}', skipping ticks table upsert", tick.label());
        }
    }

    /** Upserts a completed snapshot (all tracked labels populated) as one row in the {@code tick_snapshot} table,
     * keyed by today's date + tick time so ticks from different trading days never collide. */
    private void persistSnapshot(TickSnapshot snapshot) {
        jdbcTemplate.update("""
                INSERT INTO tick_snapshot (
                    tick_date, tick_time, nifty,
                    atm_ce, atm_ce_strike, atm_ce_delta,
                    atm_pe, atm_pe_strike, atm_pe_delta,
                    fixed_itm_ce, fixed_itm_ce_strike, fixed_itm_ce_delta,
                    fixed_itm_pe, fixed_itm_pe_strike, fixed_itm_pe_delta,
                    nifty_fut
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tick_date, tick_time) DO UPDATE SET
                    nifty = EXCLUDED.nifty,
                    atm_ce = EXCLUDED.atm_ce,
                    atm_ce_strike = EXCLUDED.atm_ce_strike,
                    atm_ce_delta = EXCLUDED.atm_ce_delta,
                    atm_pe = EXCLUDED.atm_pe,
                    atm_pe_strike = EXCLUDED.atm_pe_strike,
                    atm_pe_delta = EXCLUDED.atm_pe_delta,
                    fixed_itm_ce = EXCLUDED.fixed_itm_ce,
                    fixed_itm_ce_strike = EXCLUDED.fixed_itm_ce_strike,
                    fixed_itm_ce_delta = EXCLUDED.fixed_itm_ce_delta,
                    fixed_itm_pe = EXCLUDED.fixed_itm_pe,
                    fixed_itm_pe_strike = EXCLUDED.fixed_itm_pe_strike,
                    fixed_itm_pe_delta = EXCLUDED.fixed_itm_pe_delta,
                    nifty_fut = EXCLUDED.nifty_fut
                """,
                LocalDate.now(), snapshot.tickTime().toString(), snapshot.nifty(),
                snapshot.atmCe(), snapshot.atmCeStrike(), snapshot.atmCeDelta(),
                snapshot.atmPe(), snapshot.atmPeStrike(), snapshot.atmPeDelta(),
                snapshot.fixedItmCe(), snapshot.fixedItmCeStrike(), snapshot.fixedItmCeDelta(),
                snapshot.fixedItmPe(), snapshot.fixedItmPeStrike(), snapshot.fixedItmPeDelta(),
                snapshot.niftyFut());
    }
}
