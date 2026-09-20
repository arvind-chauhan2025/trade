package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for mocking {@link com.example.demo.dto.TickSnapshot} data instead of relying on the
 * real Angel One SmartStream feed. Useful for testing {@code TickSnapshotBroadcastService} (WebSocket
 * broadcast + enriched-snapshot persistence) outside market hours, when no live ticks are streaming.
 * When {@link #isEnabled()} is {@code true}, {@code MockSnapshotService} generates a new snapshot every
 * broadcast cycle via a small random walk from the configured base prices, instead of reading
 * {@code TickPersistenceService.getLatestCompleteSnapshot()}.
 * <p>
 * Populate in application.properties, e.g.:
 * <pre>
 * snapshot.mock.enabled=true
 * snapshot.mock.base-nifty=24800
 * snapshot.mock.base-atm-ce=120
 * snapshot.mock.base-atm-pe=110
 * snapshot.mock.base-fixed-itm-ce=250
 * snapshot.mock.base-fixed-itm-pe=60
 * snapshot.mock.base-nifty-fut=24810
 * snapshot.mock.nifty-jitter=5.0
 * snapshot.mock.option-jitter=1.5
 * </pre>
 */
@ConfigurationProperties(prefix = "snapshot.mock")
public class MockSnapshotProperties {

    /** When true, {@code TickSnapshotBroadcastService} broadcasts/persists dynamically generated dummy
     * snapshots instead of the real latest complete snapshot. Default false. */
    private boolean enabled = false;

    private double baseNifty = 24_800;
    private double baseAtmCe = 120;
    private double baseAtmPe = 110;
    private double baseFixedItmCe = 250;
    private double baseFixedItmPe = 60;
    private double baseNiftyFut = 24_810;

    /** Max absolute random-walk step applied to NIFTY/NIFTY FUT on each generated snapshot. */
    private double niftyJitter = 5.0;

    /** Max absolute random-walk step applied to each option premium on each generated snapshot. */
    private double optionJitter = 1.5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getBaseNifty() {
        return baseNifty;
    }

    public void setBaseNifty(double baseNifty) {
        this.baseNifty = baseNifty;
    }

    public double getBaseAtmCe() {
        return baseAtmCe;
    }

    public void setBaseAtmCe(double baseAtmCe) {
        this.baseAtmCe = baseAtmCe;
    }

    public double getBaseAtmPe() {
        return baseAtmPe;
    }

    public void setBaseAtmPe(double baseAtmPe) {
        this.baseAtmPe = baseAtmPe;
    }

    public double getBaseFixedItmCe() {
        return baseFixedItmCe;
    }

    public void setBaseFixedItmCe(double baseFixedItmCe) {
        this.baseFixedItmCe = baseFixedItmCe;
    }

    public double getBaseFixedItmPe() {
        return baseFixedItmPe;
    }

    public void setBaseFixedItmPe(double baseFixedItmPe) {
        this.baseFixedItmPe = baseFixedItmPe;
    }

    public double getBaseNiftyFut() {
        return baseNiftyFut;
    }

    public void setBaseNiftyFut(double baseNiftyFut) {
        this.baseNiftyFut = baseNiftyFut;
    }

    public double getNiftyJitter() {
        return niftyJitter;
    }

    public void setNiftyJitter(double niftyJitter) {
        this.niftyJitter = niftyJitter;
    }

    public double getOptionJitter() {
        return optionJitter;
    }

    public void setOptionJitter(double optionJitter) {
        this.optionJitter = optionJitter;
    }
}
