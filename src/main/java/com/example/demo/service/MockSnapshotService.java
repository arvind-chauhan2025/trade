package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.config.MockSnapshotProperties;
import com.example.demo.dto.OptionContract;
import com.example.demo.dto.TickSnapshot;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates dynamic dummy {@link TickSnapshot} data for {@link TickSnapshotBroadcastService} to
 * broadcast/persist when {@link MockSnapshotProperties#isEnabled()} is {@code true}. Each call to
 * {@link #nextSnapshot()} takes a small random walk from the previously generated prices (starting from
 * the configured base prices), so values change tick-to-tick like a real feed would, without requiring
 * the real Angel One SmartStream connection — useful for testing outside market hours.
 */
@Service
public class MockSnapshotService {

    private final MockSnapshotProperties mockSnapshotProperties;
    private final AngelOneProperties angelOneProperties;

    private volatile double nifty;
    private volatile double atmCe;
    private volatile double atmPe;
    private volatile double fixedItmCe;
    private volatile double fixedItmPe;
    private volatile double niftyFut;
    private volatile double atmStrike;
    private volatile double fixedItmCeStrike;
    private volatile double fixedItmPeStrike;
    private volatile boolean initialized;

    public MockSnapshotService(MockSnapshotProperties mockSnapshotProperties, AngelOneProperties angelOneProperties) {
        this.mockSnapshotProperties = mockSnapshotProperties;
        this.angelOneProperties = angelOneProperties;
    }

    /** Returns a new, fully complete {@link TickSnapshot} with the current time and randomly-walked
     * prices (initialized from the configured base prices on the first call). Strikes are derived from
     * the current mock NIFTY price so they stay consistent with it, and Deltas are fixed, representative
     * values (0.5/-0.5 for ATM, 0.7/-0.7 for the deeper fixed ITM legs). */
    public synchronized TickSnapshot nextSnapshot() {
        if (!initialized) {
            nifty = mockSnapshotProperties.getBaseNifty();
            atmCe = mockSnapshotProperties.getBaseAtmCe();
            atmPe = mockSnapshotProperties.getBaseAtmPe();
            fixedItmCe = mockSnapshotProperties.getBaseFixedItmCe();
            fixedItmPe = mockSnapshotProperties.getBaseFixedItmPe();
            niftyFut = mockSnapshotProperties.getBaseNiftyFut();
            initialized = true;
        } else {
            nifty = walk(nifty, mockSnapshotProperties.getNiftyJitter());
            niftyFut = walk(niftyFut, mockSnapshotProperties.getNiftyJitter());
            atmCe = walk(atmCe, mockSnapshotProperties.getOptionJitter());
            atmPe = walk(atmPe, mockSnapshotProperties.getOptionJitter());
            fixedItmCe = walk(fixedItmCe, mockSnapshotProperties.getOptionJitter());
            fixedItmPe = walk(fixedItmPe, mockSnapshotProperties.getOptionJitter());
        }

        int strikeStep = angelOneProperties.getStrikeStep();
        double itmOffset = strikeStep * (double) angelOneProperties.getItmDepth();
        atmStrike = Math.round(nifty / strikeStep) * (double) strikeStep;
        fixedItmCeStrike = atmStrike - itmOffset;
        fixedItmPeStrike = atmStrike + itmOffset;

        return new TickSnapshot(
                LocalTime.now().truncatedTo(ChronoUnit.SECONDS),
                nifty,
                atmCe, atmStrike, 0.5,
                atmPe, atmStrike, -0.5,
                fixedItmCe, fixedItmCeStrike, 0.7,
                fixedItmPe, fixedItmPeStrike, -0.7,
                niftyFut);
    }

    /** Returns dummy {@link OptionContract}s (matching the strikes from the most recent
     * {@link #nextSnapshot()} call) for the four tracked labels, so {@link GreeksCacheService} can be fed
     * mock Greeks without requiring the real Angel One pipeline (Scrip Master, auth, etc.) to have run. */
    public synchronized Map<String, OptionContract> currentContracts() {
        LocalDate today = LocalDate.now();
        return Map.of(
                "ATM CE", new OptionContract("MOCK", "MOCK-ATM-CE", "NIFTY", "NFO-OPT", "OPTIDX", today, atmStrike, "CE", 1),
                "ATM PE", new OptionContract("MOCK", "MOCK-ATM-PE", "NIFTY", "NFO-OPT", "OPTIDX", today, atmStrike, "PE", 1),
                "FIXED ITM CE", new OptionContract("MOCK", "MOCK-ITM-CE", "NIFTY", "NFO-OPT", "OPTIDX", today, fixedItmCeStrike, "CE", 1),
                "FIXED ITM PE", new OptionContract("MOCK", "MOCK-ITM-PE", "NIFTY", "NFO-OPT", "OPTIDX", today, fixedItmPeStrike, "PE", 1));
    }

    /** Random-walks {@code current} by up to +/- {@code jitter}, floored so prices never go non-positive. */
    private double walk(double current, double jitter) {
        double change = ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        return Math.max(0.05, current + change);
    }
}
