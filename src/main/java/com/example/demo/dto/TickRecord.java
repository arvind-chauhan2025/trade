package com.example.demo.dto;

/**
 * A single decoded LTP tick for one of the tracked instruments
 * (NIFTY, ATM CE, ATM PE, FIXED ITM CE, FIXED ITM PE, NIFTY FUT).
 * <p>
 * {@code label} of {@code "NIFTY"} identifies an index (NSE_CM) tick;
 * any other label identifies an option/future (NSE_FO) tick. {@code strike}
 * is the contract's strike price (from {@link com.example.demo.dto.OptionContract#strike()});
 * it is {@code 0.0} for NIFTY and NIFTY FUT, which have no strike.
 */
public record TickRecord(
        String label,
        String token,
        double price,
        double strike,
        long exchangeTimestampMillis,
        String tickTime
) {
    public boolean isIndexTick() {
        return "NIFTY".equals(label);
    }
}

