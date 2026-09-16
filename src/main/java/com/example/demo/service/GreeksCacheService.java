package com.example.demo.service;

import com.example.demo.dto.OptionContract;
import com.example.demo.dto.OptionGreek;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches Option Greeks for the tracked expiry and caches the Delta for each
 * of our tracked contracts (ATM CE, ATM PE, FIXED ITM CE, FIXED ITM PE),
 * keyed by the same labels used elsewhere in the pipeline. This makes Delta
 * available to the existing in-memory tick calculations without requiring
 * per-tick Greeks calls (Angel One's Greeks API is a REST snapshot, not a
 * streamed value).
 */
@Service
public class GreeksCacheService {

    private static final Logger log = LoggerFactory.getLogger(GreeksCacheService.class);

    private final AngelOneOptionGreeksService greeksService;
    private final ConcurrentHashMap<String, Double> deltaByLabel = new ConcurrentHashMap<>();

    public GreeksCacheService(AngelOneOptionGreeksService greeksService) {
        this.greeksService = greeksService;
    }

    /**
     * Fetches Greeks for {@code expiry} and updates the cached Delta for each
     * labeled contract, matching by exact strike + option type.
     */
    public void refresh(LocalDate expiry, Map<String, OptionContract> trackedContracts) {
        List<OptionGreek> greeks = greeksService.getGreeks(expiry);

        trackedContracts.forEach((label, contract) -> greeks.stream()
                .filter(g -> g.optionType().equalsIgnoreCase(contract.optionType()) && g.strike() == contract.strike())
                .findFirst()
                .ifPresentOrElse(
                        g -> {
                            deltaByLabel.put(label, g.delta());
                            log.info("{} Delta = {} (strike={}, expiry={})", label, g.delta(), contract.strike(), expiry);
                        },
                        () -> log.warn("No Greeks entry found for {} strike={} expiry={}", label, contract.strike(), expiry)
                ));
    }

    /** Returns the cached Delta for a label (ATM CE, ATM PE, FIXED ITM CE, FIXED ITM PE), or null if unavailable. */
    public Double getDelta(String label) {
        return deltaByLabel.get(label);
    }

    /** Returns an immutable snapshot of all cached Deltas. */
    public Map<String, Double> getAllDeltas() {
        return Map.copyOf(deltaByLabel);
    }
}

