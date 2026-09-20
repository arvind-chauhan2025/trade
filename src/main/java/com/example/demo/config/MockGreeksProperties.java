package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for mocking Option Greeks (Delta/Gamma/Theta/Vega/IV) instead of calling Angel One's
 * Greeks REST API. Useful for testing the pipeline outside market hours, when Angel One doesn't serve
 * live Greeks. When {@link #isEnabled()} is {@code true}, {@link com.example.demo.service.GreeksCacheService}
 * synthesizes an {@link com.example.demo.dto.OptionGreek} per tracked label instead of fetching real data.
 * <p>
 * Populate in application.properties, e.g.:
 * <pre>
 * greeks.mock.enabled=true
 * greeks.mock.delta=0.5
 * greeks.mock.gamma=0.002
 * greeks.mock.theta=-5.0
 * greeks.mock.vega=10.0
 * greeks.mock.implied-volatility=15.0
 * </pre>
 */
@ConfigurationProperties(prefix = "greeks.mock")
public class MockGreeksProperties {

    /** When true, Greeks are synthesized locally instead of fetched from Angel One. Default false. */
    private boolean enabled = false;

    /** Mock |Delta| magnitude; applied as positive for CE contracts and negated for PE contracts. */
    private double delta = 0.5;

    /** Mock Gamma, applied identically to CE and PE contracts. */
    private double gamma = 0.002;

    /** Mock Theta (per day), sign preserved as configured (typically negative, matching real decay). */
    private double theta = -5.0;

    /** Mock Vega, applied identically to CE and PE contracts. */
    private double vega = 10.0;

    /** Mock implied volatility (%), applied identically to CE and PE contracts. */
    private double impliedVolatility = 15.0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getDelta() {
        return delta;
    }

    public void setDelta(double delta) {
        this.delta = delta;
    }

    public double getGamma() {
        return gamma;
    }

    public void setGamma(double gamma) {
        this.gamma = gamma;
    }

    public double getTheta() {
        return theta;
    }

    public void setTheta(double theta) {
        this.theta = theta;
    }

    public double getVega() {
        return vega;
    }

    public void setVega(double vega) {
        this.vega = vega;
    }

    public double getImpliedVolatility() {
        return impliedVolatility;
    }

    public void setImpliedVolatility(double impliedVolatility) {
        this.impliedVolatility = impliedVolatility;
    }
}
