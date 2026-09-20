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
}
