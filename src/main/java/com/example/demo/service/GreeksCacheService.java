package com.example.demo.service;

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
 */
@Service
public class GreeksCacheService {

    private static final Logger log = LoggerFactory.getLogger(GreeksCacheService.class);

    private final AngelOneOptionGreeksService greeksService;
    private final ConcurrentHashMap<String, OptionGreek> greeksByLabel = new ConcurrentHashMap<>();

    public GreeksCacheService(AngelOneOptionGreeksService greeksService) {
        this.greeksService = greeksService;
    }

    /**
     * Fetches Greeks for {@code expiry} and updates the cached Greek set for each
     * labeled contract, matching by exact strike + option type.
     */
    public void refresh(LocalDate expiry, Map<String, OptionContract> trackedContracts) {
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

