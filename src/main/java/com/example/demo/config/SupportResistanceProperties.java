package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for automatic Support/Resistance detection from completed 5-minute NIFTY candles.
 * <p>
 * Populate in application.properties, e.g.:
 * <pre>
 * sr.pivot-window=2
 * sr.zone-tolerance-points=20
 * </pre>
 */
@ConfigurationProperties(prefix = "sr")
public class SupportResistanceProperties {

    /** Number of candles required on each side of a candidate candle for it to count as a swing
     * high/low (e.g. 2 means the candle's high/low must be the extreme among itself + 2 candles before
     * + 2 candles after). */
    private int pivotWindow = 2;

    /** Max gap (in NIFTY points) between two swing prices for them to be merged into the same
     * Support/Resistance zone. */
    private double zoneTolerancePoints = 20.0;

    /** Maximum number of previous trading sessions (excluding the current one) to progressively search
     * for previous-session High/Low/Close levels when the current session alone doesn't yield at least 2
     * valid Support and 2 valid Resistance zones near the current spot. Sessions are derived from which
     * trade dates actually have persisted candles, so weekends/holidays are skipped automatically. */
    private int maxPreviousSessions = 5;

    public int getPivotWindow() {
        return pivotWindow;
    }

    public void setPivotWindow(int pivotWindow) {
        this.pivotWindow = pivotWindow;
    }

    public double getZoneTolerancePoints() {
        return zoneTolerancePoints;
    }

    public void setZoneTolerancePoints(double zoneTolerancePoints) {
        this.zoneTolerancePoints = zoneTolerancePoints;
    }

    public int getMaxPreviousSessions() {
        return maxPreviousSessions;
    }

    public void setMaxPreviousSessions(int maxPreviousSessions) {
        this.maxPreviousSessions = maxPreviousSessions;
    }
}
