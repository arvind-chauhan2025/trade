package com.example.demo.service;

import com.example.demo.config.MockGreeksProperties;
import com.example.demo.dto.OptionContract;
import com.example.demo.dto.OptionGreek;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches Option Greeks for the tracked expiry and caches the full Greek set (Delta, Gamma, Theta, ...)
 * for each of our tracked contracts (ATM CE, ATM PE, FIXED ITM CE, FIXED ITM PE), keyed by the same
 * labels used elsewhere in the pipeline. This makes Delta/Gamma/Theta available to the existing
 * in-memory tick calculations without requiring per-tick Greeks calls (Angel One's Greeks API is a
 * REST snapshot, not a streamed value).
 * <p>
 * When {@link MockGreeksProperties#isEnabled()} is {@code true} (e.g. {@code greeks.mock.enabled=true}),
 * Greeks are synthesized locally from the configured mock values instead of being fetched from Angel
 * One, so the pipeline can be exercised outside market hours when the real Greeks API has no data.
 */
@Service
public class GreeksCacheService {

    private static final Logger log = LoggerFactory.getLogger(GreeksCacheService.class);

    private final AngelOneOptionGreeksService greeksService;
    private final MockGreeksProperties mockGreeksProperties;
    private final ConcurrentHashMap<String, OptionGreek> greeksByLabel = new ConcurrentHashMap<>();

    public GreeksCacheService(AngelOneOptionGreeksService greeksService, MockGreeksProperties mockGreeksProperties) {
        this.greeksService = greeksService;
        this.mockGreeksProperties = mockGreeksProperties;
    }

    /**
     * Updates the cached Greek set for each labeled contract for {@code expiry}. If
     * {@code greeks.mock.enabled=true}, synthesizes Greeks locally from the configured mock values;
     * otherwise fetches real Greeks from Angel One and matches by exact strike + option type.
     */
    public void refresh(LocalDate expiry, Map<String, OptionContract> trackedContracts) {
        if (mockGreeksProperties.isEnabled()) {
            refreshWithMockData(expiry, trackedContracts);
            return;
        }

        log.info("Refreshing Greeks for expiry={}", expiry);
        List<OptionGreek> greeks = greeksService.getGreeks(expiry);

        trackedContracts.forEach((label, contract) -> greeks.stream()
                .filter(g -> g.optionType().equalsIgnoreCase(contract.optionType()) && g.strike() == contract.strike())
                .findFirst()
                .ifPresentOrElse(
                        g -> {
                            greeksByLabel.put(label, g);
                            log.info("{} Delta={} Gamma={} Theta={} (strike={}, expiry={})",
                                    label, g.delta(), g.gamma(), g.theta(), contract.strike(), expiry);
                        },
                        () -> log.warn("No Greeks entry found for {} strike={} expiry={}", label, contract.strike(), expiry)
                ));
    }

    /** Synthesizes an {@link OptionGreek} per tracked label from {@link MockGreeksProperties}, using each
     * contract's real strike/optionType but configured mock Delta/Gamma/Theta/Vega/IV. Delta's sign
     * follows the option type (positive for CE, negative for PE), matching real-world convention. */
    private void refreshWithMockData(LocalDate expiry, Map<String, OptionContract> trackedContracts) {
        log.info("greeks.mock.enabled=true; synthesizing mock Greeks for expiry={} instead of calling Angel One", expiry);
        trackedContracts.forEach((label, contract) -> {
            boolean isCall = contract.optionType().equalsIgnoreCase("CE");
            double delta = isCall ? Math.abs(mockGreeksProperties.getDelta()) : -Math.abs(mockGreeksProperties.getDelta());
            OptionGreek mock = new OptionGreek(
                    contract.strike(), contract.optionType(),
                    delta, mockGreeksProperties.getGamma(), mockGreeksProperties.getTheta(),
                    mockGreeksProperties.getVega(), mockGreeksProperties.getImpliedVolatility());
            greeksByLabel.put(label, mock);
            log.info("{} (mock) Delta={} Gamma={} Theta={} (strike={}, expiry={})",
                    label, mock.delta(), mock.gamma(), mock.theta(), contract.strike(), expiry);
        });
    }

    /** Returns the cached Delta for a label (ATM CE, ATM PE, FIXED ITM CE, FIXED ITM PE), or null if unavailable. */
    public Double getDelta(String label) {
        OptionGreek greek = greeksByLabel.get(label);
        return greek != null ? greek.delta() : null;
    }

    /** Returns the cached Gamma for a label, or null if unavailable. */
    public Double getGamma(String label) {
        OptionGreek greek = greeksByLabel.get(label);
        return greek != null ? greek.gamma() : null;
    }

    /** Returns the cached Theta for a label (per day, sign as returned by the broker), or null if unavailable. */
    public Double getTheta(String label) {
        OptionGreek greek = greeksByLabel.get(label);
        return greek != null ? greek.theta() : null;
    }

    /** Returns the full cached {@link OptionGreek} for a label, or null if unavailable. */
    public OptionGreek getGreek(String label) {
        return greeksByLabel.get(label);
    }

    /** Returns an immutable snapshot of all cached Deltas. */
    public Map<String, Double> getAllDeltas() {
        Map<String, Double> deltas = new java.util.HashMap<>();
        greeksByLabel.forEach((label, greek) -> deltas.put(label, greek.delta()));
        return Map.copyOf(deltas);
    }
}

