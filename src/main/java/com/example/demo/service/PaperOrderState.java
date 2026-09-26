package com.example.demo.service;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Mutable in-memory state for one paper (simulated, never sent to Angel One) CE/PE trade managed by
 * {@link PaperTradingService}. Mirrors the {@code paper_order} table row; kept as a small in-process
 * object so the engine can update it tick-by-tick (highest premium seen, hypothetical SL) without a
 * database round-trip on every 5-second snapshot.
 */
class PaperOrderState {

    Long id;
    final LocalDate tradeDate;
    final String direction; // "CE" or "PE"
    final LocalTime entryTime;
    final double entryNifty;
    final double entryPremium;
    final Double entryStrike;
    final PremiumReferenceService.FixedItmOrderReference reference;

    /** Highest premium observed since entry; drives the hypothetical trailing SL, analysis-only. */
    double highestPremium;
    boolean hypotheticalSlHit;
    Double hypotheticalSlExitPrice;
    LocalTime hypotheticalSlExitTime;
    Double hypotheticalSlPnl;

    PaperOrderState(LocalDate tradeDate, String direction, LocalTime entryTime, double entryNifty,
                     double entryPremium, Double entryStrike, PremiumReferenceService.FixedItmOrderReference reference) {
        this.tradeDate = tradeDate;
        this.direction = direction;
        this.entryTime = entryTime;
        this.entryNifty = entryNifty;
        this.entryPremium = entryPremium;
        this.entryStrike = entryStrike;
        this.reference = reference;
        this.highestPremium = entryPremium;
    }
}
