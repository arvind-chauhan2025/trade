package com.example.demo.service;

import com.example.demo.dto.NiftyCandle;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Builds 5-minute NIFTY OHLC candles from the NIFTY price of every 5-second snapshot fed via
 * {@link #onTick(LocalDate, LocalTime, double)} (called from {@link TickSnapshotBroadcastService}'s
 * 5-second broadcast cycle). Candles are aligned to clock-based 5-minute buckets (09:15:00-09:19:59,
 * 09:20:00-09:24:59, ...). The currently-forming candle is kept only in memory; once a tick belongs to a
 * new bucket, the just-finished candle is persisted into the {@code nifty_candle_5m} table and returned
 * to the caller so it can trigger downstream work (e.g. Support/Resistance recalculation). Historical
 * candles already persisted are never modified or deleted by this service.
 */
@Service
public class NiftyCandleService {

    private static final Logger log = LoggerFactory.getLogger(NiftyCandleService.class);
    private static final int INTERVAL_MINUTES = 5;

    private final JdbcTemplate jdbcTemplate;

    // Currently forming candle (in-memory only; persisted once its 5-minute bucket completes).
    private LocalDate formingDate;
    private LocalTime formingIntervalStart;
    private double open;
    private double high;
    private double low;
    private double close;
    private int tickCount;
    private boolean formingInitialized;

    public NiftyCandleService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    void start() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS nifty_candle_5m (
                    trade_date DATE NOT NULL,
                    interval_start VARCHAR(8) NOT NULL,
                    open_price DOUBLE PRECISION NOT NULL,
                    high_price DOUBLE PRECISION NOT NULL,
                    low_price DOUBLE PRECISION NOT NULL,
                    close_price DOUBLE PRECISION NOT NULL,
                    tick_count INTEGER NOT NULL,
                    PRIMARY KEY (trade_date, interval_start)
                )
                """);
    }

    /** Feeds one NIFTY price observation into the currently-forming 5-minute candle: updates
     * High/Low/Close if the tick belongs to the same 5-minute bucket as the forming candle, or persists
     * the just-finished candle and starts a new one if the tick belongs to a later bucket. Returns the
     * just-completed candle (already persisted) if this tick rolled over into a new bucket, or empty if
     * it merely updated the still-forming candle (nothing to act on downstream yet). */
    public synchronized Optional<NiftyCandle> onTick(LocalDate tradeDate, LocalTime tickTime, double niftyPrice) {
        LocalTime bucketStart = floorToInterval(tickTime);

        if (!formingInitialized) {
            startNewCandle(tradeDate, bucketStart, niftyPrice);
            return Optional.empty();
        }

        if (formingDate.equals(tradeDate) && formingIntervalStart.equals(bucketStart)) {
            high = Math.max(high, niftyPrice);
            low = Math.min(low, niftyPrice);
            close = niftyPrice;
            tickCount++;
            return Optional.empty();
        }

        NiftyCandle completed = new NiftyCandle(formingDate, formingIntervalStart, open, high, low, close, tickCount);
        persist(completed);
        startNewCandle(tradeDate, bucketStart, niftyPrice);
        return Optional.of(completed);
    }

    private void startNewCandle(LocalDate tradeDate, LocalTime bucketStart, double price) {
        formingDate = tradeDate;
        formingIntervalStart = bucketStart;
        open = price;
        high = price;
        low = price;
        close = price;
        tickCount = 1;
        formingInitialized = true;
    }

    /** Floors {@code time} down to the start of its enclosing 5-minute clock bucket, e.g. 09:17:42 -> 09:15:00. */
    private static LocalTime floorToInterval(LocalTime time) {
        int flooredMinute = time.getMinute() - (time.getMinute() % INTERVAL_MINUTES);
        return LocalTime.of(time.getHour(), flooredMinute, 0);
    }

    private void persist(NiftyCandle candle) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO nifty_candle_5m (trade_date, interval_start, open_price, high_price, low_price, close_price, tick_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (trade_date, interval_start) DO UPDATE SET
                        open_price = EXCLUDED.open_price, high_price = EXCLUDED.high_price,
                        low_price = EXCLUDED.low_price, close_price = EXCLUDED.close_price, tick_count = EXCLUDED.tick_count
                    """,
                    candle.tradeDate(), candle.intervalStart().toString(),
                    candle.open(), candle.high(), candle.low(), candle.close(), candle.tickCount());
            log.info("Persisted 5-min NIFTY candle {} {}: O={} H={} L={} C={} (ticks={})",
                    candle.tradeDate(), candle.intervalStart(), candle.open(), candle.high(), candle.low(), candle.close(), candle.tickCount());
        } catch (Exception ex) {
            log.warn("Failed to persist 5-min NIFTY candle for {} {}", candle.tradeDate(), candle.intervalStart(), ex);
        }
    }

    /** Returns every completed 5-minute candle persisted for {@code tradeDate}, ordered chronologically.
     * Does not include the currently-forming (not yet completed) candle. */
    public List<NiftyCandle> getCandles(LocalDate tradeDate) {
        return jdbcTemplate.query("""
                        SELECT trade_date, interval_start, open_price, high_price, low_price, close_price, tick_count
                        FROM nifty_candle_5m WHERE trade_date = ? ORDER BY interval_start
                        """,
                (rs, rowNum) -> new NiftyCandle(
                        rs.getObject("trade_date", LocalDate.class),
                        LocalTime.parse(rs.getString("interval_start")),
                        rs.getDouble("open_price"), rs.getDouble("high_price"),
                        rs.getDouble("low_price"), rs.getDouble("close_price"),
                        rs.getInt("tick_count")),
                tradeDate);
    }

    /** Returns every completed 5-minute candle persisted for any trade date in {@code [fromDate, toDate]}
     * (inclusive on both ends), ordered chronologically (date, then interval). */
    public List<NiftyCandle> getCandles(LocalDate fromDate, LocalDate toDate) {
        return jdbcTemplate.query("""
                        SELECT trade_date, interval_start, open_price, high_price, low_price, close_price, tick_count
                        FROM nifty_candle_5m WHERE trade_date BETWEEN ? AND ? ORDER BY trade_date, interval_start
                        """,
                (rs, rowNum) -> new NiftyCandle(
                        rs.getObject("trade_date", LocalDate.class),
                        LocalTime.parse(rs.getString("interval_start")),
                        rs.getDouble("open_price"), rs.getDouble("high_price"),
                        rs.getDouble("low_price"), rs.getDouble("close_price"),
                        rs.getInt("tick_count")),
                fromDate, toDate);
    }
}
