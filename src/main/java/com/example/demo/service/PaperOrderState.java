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
    /** How this trade was entered: {@code "FAST"} (lone divergence past
     * {@link com.example.demo.config.PaperTradingProperties#getFastEntryDivergenceThreshold()}) or
     * {@code "REGULAR"} (the normal paired bullish/bearish confirmation). */
    final String entryType;

    /** Highest premium observed since entry; drives the hypothetical trailing SL, analysis-only. */
    double highestPremium;
    boolean hypotheticalSlHit;
    Double hypotheticalSlExitPrice;
    LocalTime hypotheticalSlExitTime;
    Double hypotheticalSlPnl;

    /** Highest order-anchored divergence (CE for a CE trade, PE for a PE trade) seen since entry; drives
     * the divergence-trailing exit, analysis alongside the normal divergence-reversal exit. Null until
     * the first non-null divergence tick after entry. */
    Double peakDivergence;
    /** The order-anchored divergence value from the previous tick, used to detect a fresh consecutive
     * fall (as opposed to a fall from the peak in general) for the divergence-trailing exit. */
    Double previousDivergence;
    /** Consecutive ticks the order-anchored divergence has fallen since last making a new peak; reset to
     * 0 whenever a new peak is made or the divergence stops falling. */
    int trailingFallStreak;

    PaperOrderState(LocalDate tradeDate, String direction, LocalTime entryTime, double entryNifty,
                     double entryPremium, Double entryStrike, PremiumReferenceService.FixedItmOrderReference reference,
                     String entryType) {
        this.tradeDate = tradeDate;
        this.direction = direction;
        this.entryTime = entryTime;
        this.entryNifty = entryNifty;
        this.entryPremium = entryPremium;
        this.entryStrike = entryStrike;
        this.reference = reference;
        this.entryType = entryType;
        this.highestPremium = entryPremium;
    }
}
