package com.example.demo.dto;

import java.time.LocalTime;

/**
 * A combined view of everything that happened at a single exchange tick
 * time: the NIFTY index price (NSE_CM) together with all four option prices
 * (NSE_FO), each field simply updated in place as its tick arrives. Each
 * option field also carries its most recently cached Delta (from Angel
 * One's Option Greek API), since Greeks are fetched as a periodic REST
 * snapshot rather than streamed per tick.
 */
public record TickSnapshot(
        LocalTime tickTime,
        Double nifty,
        Double atmCe,
        Double atmCeDelta,
        Double atmPe,
        Double atmPeDelta,
        Double fixedItmCe,
        Double fixedItmCeDelta,
        Double fixedItmPe,
        Double fixedItmPeDelta
) {
    public static TickSnapshot empty(LocalTime tickTime) {
        return new TickSnapshot(tickTime, null, null, null, null, null, null, null, null, null);
    }

    /** Returns a copy with the given label's price (and cached Delta, if any) updated; other fields are preserved. */
    public TickSnapshot with(String label, double price, Double delta) {
        return switch (label) {
            case "NIFTY" -> new TickSnapshot(tickTime, price, atmCe, atmCeDelta, atmPe, atmPeDelta,
                    fixedItmCe, fixedItmCeDelta, fixedItmPe, fixedItmPeDelta);
            case "ATM CE" -> new TickSnapshot(tickTime, nifty, price, delta != null ? delta : atmCeDelta,
                    atmPe, atmPeDelta, fixedItmCe, fixedItmCeDelta, fixedItmPe, fixedItmPeDelta);
            case "ATM PE" -> new TickSnapshot(tickTime, nifty, atmCe, atmCeDelta,
                    price, delta != null ? delta : atmPeDelta, fixedItmCe, fixedItmCeDelta, fixedItmPe, fixedItmPeDelta);
            case "FIXED ITM CE" -> new TickSnapshot(tickTime, nifty, atmCe, atmCeDelta, atmPe, atmPeDelta,
                    price, delta != null ? delta : fixedItmCeDelta, fixedItmPe, fixedItmPeDelta);
            case "FIXED ITM PE" -> new TickSnapshot(tickTime, nifty, atmCe, atmCeDelta, atmPe, atmPeDelta,
                    fixedItmCe, fixedItmCeDelta, price, delta != null ? delta : fixedItmPeDelta);
            default -> this;
        };
    }
}

