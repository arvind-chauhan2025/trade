package com.example.demo.controller;

import com.example.demo.service.EnrichedSnapshotPlaybackService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Controls replaying a past trade date's already-persisted {@code enriched_snapshot} rows back over the
 * {@code /ws/tick-snapshot} WebSocket, via {@link EnrichedSnapshotPlaybackService}. Intended for reviewing
 * historical sessions on the dashboard. Optionally (with {@code backtest=true}) also runs an in-memory
 * simulated paper-trading engine alongside the replay (see {@link EnrichedSnapshotPlaybackService} class
 * doc); this never touches the live {@code PaperTradingService}/order tables. */
@RestController
public class EnrichedSnapshotPlaybackController {

    private final EnrichedSnapshotPlaybackService playbackService;

    public EnrichedSnapshotPlaybackController(EnrichedSnapshotPlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    /** Starts replaying {@code date}'s persisted enriched snapshots from the beginning, at {@code speed}x
     * the original tick pace (default {@code 15.0}). Stops any playback already in progress. When
     * {@code backtest=true} (default {@code true}), also re-runs the in-memory simulated paper-trading
     * engine over the same rows; fetch its results afterwards via {@code GET /api/playback/backtest-results}.
     * Returns how many rows were queued (0 if none exist for that date). */
    @PostMapping("/api/playback/start")
    public Map<String, Object> start(
            @RequestParam(name = "date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "speed", required = false, defaultValue = "80.0") double speed,
            @RequestParam(name = "backtest", required = false, defaultValue = "true") boolean backtest) {
        int rowCount = playbackService.start(date, speed, backtest);
        return Map.of("date", date, "speed", speed, "backtest", backtest, "rows", rowCount, "started", rowCount > 0);
    }

    /** Stops whatever playback is currently in progress (no-op if none). */
    @PostMapping("/api/playback/stop")
    public Map<String, Object> stop() {
        playbackService.stop();
        return Map.of("running", playbackService.isRunning());
    }

    /** Reports whether a playback is currently in progress and, if so, which date. */
    @GetMapping("/api/playback/status")
    public Map<String, Object> status() {
        boolean running = playbackService.isRunning();
        LocalDate date = playbackService.playingDate();
        return running ? Map.of("running", true, "date", date) : Map.of("running", false);
    }

    /** Simulated trades recorded by the current/most recent backtest run (empty if the last
     * {@code start} didn't have {@code backtest=true}, or none has run yet), plus a simple summary. */
    @GetMapping("/api/playback/backtest-results")
    public Map<String, Object> backtestResults() {
        List<EnrichedSnapshotPlaybackService.BacktestTrade> trades = playbackService.lastBacktestTrades();
        long closed = trades.stream().filter(t -> t.pnl() != null).count();
        double netPnl = trades.stream().filter(t -> t.pnl() != null).mapToDouble(EnrichedSnapshotPlaybackService.BacktestTrade::pnl).sum();
        long wins = trades.stream().filter(t -> t.pnl() != null && t.pnl() > 0).count();
        return Map.of(
                "backtestEnabled", playbackService.isBacktestEnabled(),
                "trades", trades,
                "totalTrades", trades.size(),
                "closedTrades", closed,
                "wins", wins,
                "netPnl", netPnl);
    }
}
