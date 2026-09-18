package com.example.demo.controller;

import com.example.demo.dto.TickSnapshot;
import com.example.demo.service.PremiumReferenceService;
import com.example.demo.service.TickPersistenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PremiumReferenceController {

    private final PremiumReferenceService premiumReferenceService;

    public PremiumReferenceController(PremiumReferenceService premiumReferenceService) {
        this.premiumReferenceService = premiumReferenceService;
    }

    /** Manually forces the 30-minute rolling reference to reset right now, using the latest complete
     * {@link TickSnapshot}, instead of waiting for the next scheduled 30-minute rollover. */
    @PostMapping("/api/premium-reference/rolling/refresh")
    public ResponseEntity<Map<String, Object>> refreshRollingReference() {
        TickSnapshot latest = TickPersistenceService.getLatestCompleteSnapshot();
        boolean refreshed = premiumReferenceService.refreshRollingReferenceNow(latest);
        if (!refreshed) {
            return ResponseEntity.badRequest().body(Map.of(
                    "refreshed", false,
                    "message", "No complete TickSnapshot available yet to refresh the rolling reference from."
            ));
        }
        return ResponseEntity.ok(Map.of(
                "refreshed", true,
                "tickTime", latest.tickTime().toString()
        ));
    }
}
