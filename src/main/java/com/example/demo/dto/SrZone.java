package com.example.demo.dto;

/**
 * A Support or Resistance zone clustered from nearby swing lows/highs across today's completed 5-minute
 * NIFTY candles. {@code low}/{@code high} are the zone's price range, {@code level} is the average swing
 * price within the zone, and {@code touchCount} is how many swing points were clustered into it.
 */
public record SrZone(
        String type,
        double low,
        double high,
        double level,
        int touchCount
) {
    public static final String RESISTANCE = "RESISTANCE";
    public static final String SUPPORT = "SUPPORT";
}
