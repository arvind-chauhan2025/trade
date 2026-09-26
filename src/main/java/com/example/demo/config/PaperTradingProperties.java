package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the automatic CE/PE paper-trading engine ({@code PaperTradingService}), which
 * enters/exits simulated (never sent to Angel One) trades from the existing FIXED ITM CE/PE 30-minute
 * divergence signals produced by {@code PremiumReferenceService}. Populate in application.properties,
 * e.g.:
 * <pre>
 * trading.paper.enabled=true
 * trading.paper.confirmation-ticks=6
 * trading.paper.ce-bullish-threshold=3.0
 * trading.paper.pe-bullish-threshold=-2.0
 * trading.paper.ce-bearish-threshold=-2.0
 * trading.paper.pe-bearish-threshold=3.0
 * trading.paper.sl-percentage=30.0
 * </pre>
 */
@ConfigurationProperties(prefix = "trading.paper")
public class PaperTradingProperties {

    /** Master switch for the automatic paper-trading engine. Default true. */
    private boolean enabled = true;

    /** Number of consecutive 5-second snapshots the divergence condition must hold across before an
     * entry or exit is confirmed (avoids reacting to a single noisy tick). Default 6 (~30 seconds). */
    private int confirmationTicks = 6;

    /** Entry: fixedItmCeDivergence30m must be strictly greater than this for a bullish (CE) signal. */
    private double ceBullishThreshold = 3.0;

    /** Entry: fixedItmPeDivergence30m must be strictly less than this for a bullish (CE) signal. */
    private double peBullishThreshold = -2.0;

    /** Entry: fixedItmCeDivergence30m must be strictly less than this for a bearish (PE) signal, and also
     * used (against the order-anchored CE divergence) to confirm an open CE position's exit. */
    private double ceBearishThreshold = -2.0;

    /** Entry: fixedItmPeDivergence30m must be strictly greater than this for a bearish (PE) signal, and
     * also used (against the order-anchored PE divergence) to confirm an open CE position's exit. */
    private double peBearishThreshold = 3.0;

    /** Hypothetical (analysis-only) trailing stop-loss, as a percentage drop from the highest premium
     * observed since entry. Never triggers a real exit or Angel One order. Default 30%. */
    private double slPercentage = 10.0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConfirmationTicks() {
        return confirmationTicks;
    }

    public void setConfirmationTicks(int confirmationTicks) {
        this.confirmationTicks = confirmationTicks;
    }

    public double getCeBullishThreshold() {
        return ceBullishThreshold;
    }

    public void setCeBullishThreshold(double ceBullishThreshold) {
        this.ceBullishThreshold = ceBullishThreshold;
    }

    public double getPeBullishThreshold() {
        return peBullishThreshold;
    }

    public void setPeBullishThreshold(double peBullishThreshold) {
        this.peBullishThreshold = peBullishThreshold;
    }

    public double getCeBearishThreshold() {
        return ceBearishThreshold;
    }

    public void setCeBearishThreshold(double ceBearishThreshold) {
        this.ceBearishThreshold = ceBearishThreshold;
    }

    public double getPeBearishThreshold() {
        return peBearishThreshold;
    }

    public void setPeBearishThreshold(double peBearishThreshold) {
        this.peBearishThreshold = peBearishThreshold;
    }

    public double getSlPercentage() {
        return slPercentage;
    }

    public void setSlPercentage(double slPercentage) {
        this.slPercentage = slPercentage;
    }
}
