package com.example.demo.controller;

import com.example.demo.service.EnrichedSnapshotPersistenceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Read-only endpoint for querying persisted enriched snapshots (the {@code enriched_snapshot} table,
 * whose columns grow dynamically to match {@link com.example.demo.service.PremiumReferenceService#enrich}),
 * either for the last N trading days or for an explicit start/end date-time range. Each row is returned
 * with every column present in the table at query time. */
@RestController
public class EnrichedSnapshotController {

    private final EnrichedSnapshotPersistenceService enrichedSnapshotPersistenceService;

    public EnrichedSnapshotController(EnrichedSnapshotPersistenceService enrichedSnapshotPersistenceService) {
        this.enrichedSnapshotPersistenceService = enrichedSnapshotPersistenceService;
    }

    /**
     * Returns every persisted enriched snapshot row (all columns) matching the requested filter, ordered
     * chronologically:
     * <ul>
     *     <li>{@code start} and {@code end} (ISO-8601 date-time, e.g. {@code 2024-05-01T09:15:00}): returns
     *     rows whose trade date + tick time falls within {@code [start, end]} inclusive.</li>
     *     <li>{@code days}: returns rows for the last {@code days} trading dates up to and including today
     *     (e.g. {@code days=1} means today only, {@code days=7} means the last 7 calendar dates).</li>
     *     <li>neither supplied: defaults to today only.</li>
     * </ul>
     * {@code start}/{@code end} take precedence over {@code days} if both are supplied.
     */
    @GetMapping("/api/enriched/snapshots")
    public List<Map<String, Object>> getSnapshots(
            @RequestParam(name = "days", required = false) Integer days,
            @RequestParam(name = "start", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(name = "end", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        if (start != null || end != null) {
            if (start == null || end == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both 'start' and 'end' must be supplied together");
            }
            if (start.isAfter(end)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'start' must not be after 'end'");
            }
            return enrichedSnapshotPersistenceService.getSnapshots(start, end);
        }

        LocalDate today = LocalDate.now();
        if (days == null || days <= 1) {
            return enrichedSnapshotPersistenceService.getSnapshots(today, today);
        }
        return enrichedSnapshotPersistenceService.getSnapshots(today.minusDays(days - 1L), today);
    }
}
