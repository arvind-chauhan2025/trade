package com.example.demo.dto;

/**
 * A single decoded LTP tick for one of the tracked instruments
 * (NIFTY, ATM CE, ATM PE, FIXED ITM CE, FIXED ITM PE).
 * <p>
 * {@code label} of {@code "NIFTY"} identifies an index (NSE_CM) tick;
 * any other label identifies an option (NSE_FO) tick.
 */
public record TickRecord(
        String label,
        String token,
        double price,
        long exchangeTimestampMillis,
        String tickTime
) {
    public boolean isIndexTick() {
        return "NIFTY".equals(label);
    }
}

