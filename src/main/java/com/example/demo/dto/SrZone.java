package com.example.demo.dto;

/**
 * A Support or Resistance zone clustered from nearby swing lows/highs and/or previous-session PH/PL/close
 * levels. {@code low}/{@code high} are the zone's price range, {@code level} is the average candidate
 * price within the zone, and {@code touchCount} is how many candidates (swing points, or previous-session
 * markers) were clustered into it.
 * <p>
 * {@code source} identifies where the zone's candidates came from (e.g. {@code INTRADAY_PIVOT},
 * {@code PREV_SESSION_HIGH}, {@code PREV_SESSION_LOW}, {@code PREV_SESSION_CLOSE}, or a {@code +}-joined
 * combination when independent sources confluence into the same zone). {@code sessionsBack} is how many
 * trading sessions before the current one the zone's most recent touch occurred (0 = current session).
 * {@code reactionStrength} is the largest subsequent price move (in NIFTY points) observed after a touch
 * of this zone, used as a proxy for how credible the level's historical reaction was.
 */
public record SrZone(
        String type,
        double low,
        double high,
        double level,
        int touchCount,
        String source,
        int sessionsBack,
        double reactionStrength
) {
    public static final String RESISTANCE = "RESISTANCE";
    public static final String SUPPORT = "SUPPORT";

    /** Backward-compatible constructor for callers that don't track source/recency/strength metadata. */
    public SrZone(String type, double low, double high, double level, int touchCount) {
        this(type, low, high, level, touchCount, "INTRADAY_PIVOT", 0, 0.0);
    }
}
