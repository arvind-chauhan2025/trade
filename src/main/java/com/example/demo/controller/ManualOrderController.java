package com.example.demo.controller;

import com.example.demo.dto.PlaceOrderRequest;
import com.example.demo.service.ManualOrderPersistenceService.ManualOrderSummary;
import com.example.demo.service.ManualOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Endpoints for manually placing/exiting a single CE/PE buy order against the currently tracked ATM/Fixed
 * ITM strikes, and reviewing its (and past orders') P&amp;L. See {@link ManualOrderService} for the full
 * contract. Never places a real Angel One order.
 */
@RestController
public class ManualOrderController {

    private final ManualOrderService manualOrderService;

    public ManualOrderController(ManualOrderService manualOrderService) {
        this.manualOrderService = manualOrderService;
    }

    /** Places (buys) a new manual order. Rejects with 400 if an order is already open. */
    @PostMapping("/api/manual-orders/place")
    public ManualOrderSummary place(@RequestBody PlaceOrderRequest request) {
        return manualOrderService.placeOrder(request.moneyness(), request.optionType(), request.lots());
    }

    /** Exits (sells) the currently open order; {@code orderId} must match it. */
    @PostMapping("/api/manual-orders/{orderId}/exit")
    public ManualOrderSummary exit(@PathVariable long orderId) {
        return manualOrderService.exitOrder(orderId);
    }

    /** Returns the currently open manual order, if any. */
    @GetMapping("/api/manual-orders/open")
    public Optional<ManualOrderSummary> getOpenOrder() {
        return manualOrderService.getOpenOrder();
    }

    /** Returns every manual order (open or closed) for the last {@code days} trading dates up to and
     * including today (default 1, i.e. today only). */
    @GetMapping("/api/manual-orders")
    public List<ManualOrderSummary> getOrders(@RequestParam(name = "days", required = false) Integer days) {
        return manualOrderService.getOrders(days);
    }
}
