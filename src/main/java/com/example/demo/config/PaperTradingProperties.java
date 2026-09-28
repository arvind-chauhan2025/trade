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
 * trading.paper.ce-exit-threshold=1.0
 * trading.paper.pe-exit-threshold=-1.0
 * trading.paper.ce-divergence-trailing-retracement=1.0
 * trading.paper.pe-divergence-trailing-retracement=1.0
 * trading.paper.divergence-trailing-confirmation-count=2
 * trading.paper.fast-entry-divergence-threshold=8.0
 * trading.paper.fast-entry-confirmation-ticks=2
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

    /** Exit: a much weaker (closer-to-neutral) threshold than the entry thresholds, so a trade exits as
     * soon as the divergence starts reverting rather than waiting for a full opposite-signal reversal.
     * A CE trade exits once the order-anchored CE divergence drops below this value (default 1.0); a PE
     * trade exits once the order-anchored CE divergence rises above the negation of this value. */
    private double ceExitThreshold = 1.0;

    /** Exit: a much weaker (closer-to-neutral) threshold than the entry thresholds. A CE trade exits once
     * the order-anchored PE divergence rises above this value (default -1.0); a PE trade exits once the
     * order-anchored PE divergence drops below the negation of this value. */
    private double peExitThreshold = -1.0;

    /** Divergence-trailing exit (CE trade): once the order-anchored CE divergence has retraced by at
     * least this much from its highest value seen since entry (the "peak"), combined with
     * {@link #divergenceTrailingConfirmationCount} consecutive falling ticks, the trade exits — a
     * trailing-stop analogue for divergence instead of premium. Evaluated alongside (not instead of) the
     * existing {@link #ceExitThreshold}/{@link #peExitThreshold} reversal exit. Default 1.0. */
    private double ceDivergenceTrailingRetracement = 1.0;

    /** Divergence-trailing exit (PE trade): mirrors {@link #ceDivergenceTrailingRetracement}, but tracks
     * the peak order-anchored PE divergence since entry instead of CE. Default 1.0. */
    private double peDivergenceTrailingRetracement = 1.0;

    /** Number of consecutive falling snapshots (order-anchored divergence strictly lower than the
     * previous tick, after having peaked) required before the divergence-trailing exit above can fire.
     * Default 2. */
    private int divergenceTrailingConfirmationCount = 2;

    /** Fast entry: when {@code fixedItmCeDivergence30m} or {@code fixedItmPeDivergence30m} alone (not
     * the paired bullish/bearish condition) exceeds this value, a CE (for CE divergence) or PE (for PE
     * divergence) trade is opened after only {@link #fastEntryConfirmationTicks} consecutive snapshots
     * instead of waiting for the full {@link #confirmationTicks} count — for a signal strong enough on
     * its own to skip the normal, slower confirmation. Default 8.0. */
    private double fastEntryDivergenceThreshold = 8.0;

    /** Number of consecutive snapshots {@code fixedItmCeDivergence30m}/{@code fixedItmPeDivergence30m}
     * must exceed {@link #fastEntryDivergenceThreshold} before the fast entry above fires. Default 2. */
    private int fastEntryConfirmationTicks = 2;

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

    public double getCeExitThreshold() {
        return ceExitThreshold;
    }

    public void setCeExitThreshold(double ceExitThreshold) {
        this.ceExitThreshold = ceExitThreshold;
    }

    public double getPeExitThreshold() {
        return peExitThreshold;
    }

    public void setPeExitThreshold(double peExitThreshold) {
        this.peExitThreshold = peExitThreshold;
    }

    public double getCeDivergenceTrailingRetracement() {
        return ceDivergenceTrailingRetracement;
    }

    public void setCeDivergenceTrailingRetracement(double ceDivergenceTrailingRetracement) {
        this.ceDivergenceTrailingRetracement = ceDivergenceTrailingRetracement;
    }

    public double getPeDivergenceTrailingRetracement() {
        return peDivergenceTrailingRetracement;
    }

    public void setPeDivergenceTrailingRetracement(double peDivergenceTrailingRetracement) {
        this.peDivergenceTrailingRetracement = peDivergenceTrailingRetracement;
    }

    public int getDivergenceTrailingConfirmationCount() {
        return divergenceTrailingConfirmationCount;
    }

    public void setDivergenceTrailingConfirmationCount(int divergenceTrailingConfirmationCount) {
        this.divergenceTrailingConfirmationCount = divergenceTrailingConfirmationCount;
    }

    public double getFastEntryDivergenceThreshold() {
        return fastEntryDivergenceThreshold;
    }

    public void setFastEntryDivergenceThreshold(double fastEntryDivergenceThreshold) {
        this.fastEntryDivergenceThreshold = fastEntryDivergenceThreshold;
    }

    public int getFastEntryConfirmationTicks() {
        return fastEntryConfirmationTicks;
    }

    public void setFastEntryConfirmationTicks(int fastEntryConfirmationTicks) {
        this.fastEntryConfirmationTicks = fastEntryConfirmationTicks;
    }

    public double getSlPercentage() {
        return slPercentage;
    }

    public void setSlPercentage(double slPercentage) {
        this.slPercentage = slPercentage;
    }
}
