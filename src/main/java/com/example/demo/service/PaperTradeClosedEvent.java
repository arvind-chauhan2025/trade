package com.example.demo.service;

import java.time.LocalTime;

/**
 * Published by {@link PaperTradingService} immediately after a paper trade's real, divergence-based exit
 * closes it. {@code TradingApplication} listens for this to immediately re-check whether its 30-minute
 * ATM/ITM rolling re-selection was overdue but deferred while this trade was open (see
 * {@link PaperTradingService#hasOpenPosition()}), instead of waiting up to a minute for its next
 * scheduled check.
 */
public record PaperTradeClosedEvent(long orderId, String direction, LocalTime exitTime) {
}
