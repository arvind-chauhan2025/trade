package com.example.demo.controller;

import com.example.demo.service.OrderSnapshotPersistenceService;
import com.example.demo.service.PaperOrderPersistenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Read-only endpoints for reviewing simulated (paper) CE/PE trades opened by {@code PaperTradingService}
 * and their per-tick divergence audit trail. No endpoint here ever places or affects a real Angel One
 * order — this is purely a backtesting ledger. */
@RestController
public class PaperTradingController {

    private final PaperOrderPersistenceService orderPersistenceService;
    private final OrderSnapshotPersistenceService snapshotPersistenceService;

    public PaperTradingController(PaperOrderPersistenceService orderPersistenceService,
                                   OrderSnapshotPersistenceService snapshotPersistenceService) {
        this.orderPersistenceService = orderPersistenceService;
        this.snapshotPersistenceService = snapshotPersistenceService;
    }

    /** Returns every paper trade (open or closed) for the last {@code days} trading dates up to and
     * including today (default 1, i.e. today only), including its entry/exit prices, real divergence-based
     * exit reason/P&L, and the hypothetical (analysis-only) trailing-SL outcome for comparison. */
    @GetMapping("/api/paper-trading/orders")
    public List<Map<String, Object>> getOrders(@RequestParam(name = "days", required = false) Integer days) {
        LocalDate today = LocalDate.now();
        LocalDate from = (days == null || days <= 1) ? today : today.minusDays(days - 1L);
        return orderPersistenceService.getOrders(from, today);
    }

    /** Returns the full per-5-second-tick divergence/price audit trail for one paper trade (entry tick
     * through exit tick), used to reconstruct exactly why/when it fired. */
    @GetMapping("/api/paper-trading/orders/{orderId}/snapshots")
    public List<Map<String, Object>> getOrderSnapshots(@PathVariable long orderId) {
        return snapshotPersistenceService.getSnapshots(orderId);
    }
}
