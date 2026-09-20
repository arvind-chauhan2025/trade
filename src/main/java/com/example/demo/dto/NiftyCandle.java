package com.example.demo.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A single completed (or currently forming) 5-minute NIFTY OHLC candle, aligned to clock-based 5-minute
 * buckets (e.g. 09:15:00-09:19:59, 09:20:00-09:24:59, ...), built by aggregating the NIFTY price from
 * every 5-second snapshot that falls in that bucket.
 */
public record NiftyCandle(
        LocalDate tradeDate,
        LocalTime intervalStart,
        double open,
        double high,
        double low,
        double close,
        int tickCount
) {
}
