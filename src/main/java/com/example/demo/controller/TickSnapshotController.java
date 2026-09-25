package com.example.demo.controller;

import com.example.demo.dto.DatedTickSnapshot;
import com.example.demo.service.TickPersistenceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Read-only endpoint for querying persisted, complete {@link com.example.demo.dto.TickSnapshot}s, either
 * for the last N trading days or for an explicit start/end date-time range. */
@RestController
public class TickSnapshotController {

    private final TickPersistenceService tickPersistenceService;

    public TickSnapshotController(TickPersistenceService tickPersistenceService) {
        this.tickPersistenceService = tickPersistenceService;
    }

    /**
     * Returns every complete {@link com.example.demo.dto.TickSnapshot} matching the requested filter,
     * ordered chronologically:
     * <ul>
     *     <li>{@code start} and {@code end} (ISO-8601 date-time, e.g. {@code 2024-05-01T09:15:00}): returns
     *     snapshots whose trade date + tick time falls within {@code [start, end]} inclusive.</li>
     *     <li>{@code days}: returns snapshots for the last {@code days} trading dates up to and including
     *     today (e.g. {@code days=1} means today only, {@code days=7} means the last 7 calendar dates).</li>
     *     <li>neither supplied: defaults to today only.</li>
     * </ul>
     * {@code start}/{@code end} take precedence over {@code days} if both are supplied.
     */
    @GetMapping("/api/ticks/snapshots")
    public List<DatedTickSnapshot> getSnapshots(
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
            return tickPersistenceService.getSnapshots(start, end);
        }

        LocalDate today = LocalDate.now();
        if (days == null || days <= 1) {
            return tickPersistenceService.getSnapshots(today, today);
        }
        return tickPersistenceService.getSnapshots(today.minusDays(days - 1L), today);
    }
}
