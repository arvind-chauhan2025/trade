package com.example.demo.dto;

import java.time.LocalTime;

/**
 * A combined view of everything that happened at a single exchange tick
 * time: the NIFTY index price (NSE_CM) together with all four option prices
 * (NSE_FO), each field simply updated in place as its tick arrives. Each
 * option field also carries its strike price and its most recently cached
 * Delta (from Angel One's Option Greek API), since Greeks are fetched as a
 * periodic REST snapshot rather than streamed per tick.
 */
public record TickSnapshot(
        LocalTime tickTime,
        Double nifty,
        Double atmCe,
        Double atmCeStrike,
        Double atmCeDelta,
        Double atmPe,
        Double atmPeStrike,
        Double atmPeDelta,
        Double fixedItmCe,
        Double fixedItmCeStrike,
        Double fixedItmCeDelta,
        Double fixedItmPe,
        Double fixedItmPeStrike,
        Double fixedItmPeDelta,
        Double niftyFut
) {
    public static TickSnapshot empty(LocalTime tickTime) {
        return new TickSnapshot(tickTime, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Returns a copy with the given label's price, strike (for option labels) and cached Delta (if any) updated;
     * other fields are preserved. If the freshly looked-up {@code delta} is {@code null} (e.g. the Greeks
     * cache hasn't caught up to a new strike yet, see {@link com.example.demo.service.GreeksCacheService#getDelta(String, double)}),
     * falls back to this label's previously stored Delta only when {@code strike} matches the strike that
     * old Delta was recorded for; otherwise it would silently carry a stale, wrong-strike Delta forward
     * across a strike change. */
    public TickSnapshot with(String label, double price, double strike, Double delta) {
        return switch (label) {
            case "NIFTY" -> new TickSnapshot(tickTime, price, atmCe, atmCeStrike, atmCeDelta,
                    atmPe, atmPeStrike, atmPeDelta, fixedItmCe, fixedItmCeStrike, fixedItmCeDelta,
                    fixedItmPe, fixedItmPeStrike, fixedItmPeDelta, niftyFut);
            case "ATM CE" -> new TickSnapshot(tickTime, nifty, price, strike,
                    delta != null ? delta : (sameStrike(atmCeStrike, strike) ? atmCeDelta : null),
                    atmPe, atmPeStrike, atmPeDelta, fixedItmCe, fixedItmCeStrike, fixedItmCeDelta,
                    fixedItmPe, fixedItmPeStrike, fixedItmPeDelta, niftyFut);
            case "ATM PE" -> new TickSnapshot(tickTime, nifty, atmCe, atmCeStrike, atmCeDelta,
                    price, strike, delta != null ? delta : (sameStrike(atmPeStrike, strike) ? atmPeDelta : null),
                    fixedItmCe, fixedItmCeStrike, fixedItmCeDelta,
                    fixedItmPe, fixedItmPeStrike, fixedItmPeDelta, niftyFut);
            case "FIXED ITM CE" -> new TickSnapshot(tickTime, nifty, atmCe, atmCeStrike, atmCeDelta,
                    atmPe, atmPeStrike, atmPeDelta, price, strike,
                    delta != null ? delta : (sameStrike(fixedItmCeStrike, strike) ? fixedItmCeDelta : null),
                    fixedItmPe, fixedItmPeStrike, fixedItmPeDelta, niftyFut);
            case "FIXED ITM PE" -> new TickSnapshot(tickTime, nifty, atmCe, atmCeStrike, atmCeDelta,
                    atmPe, atmPeStrike, atmPeDelta, fixedItmCe, fixedItmCeStrike, fixedItmCeDelta,
                    price, strike, delta != null ? delta : (sameStrike(fixedItmPeStrike, strike) ? fixedItmPeDelta : null),
                    niftyFut);
            case "NIFTY FUT" -> new TickSnapshot(tickTime, nifty, atmCe, atmCeStrike, atmCeDelta,
                    atmPe, atmPeStrike, atmPeDelta, fixedItmCe, fixedItmCeStrike, fixedItmCeDelta,
                    fixedItmPe, fixedItmPeStrike, fixedItmPeDelta, price);
            default -> this;
        };
    }

    private static boolean sameStrike(Double previousStrike, double currentStrike) {
        return previousStrike != null && Double.compare(previousStrike, currentStrike) == 0;
    }

    /** True once every tracked label (NIFTY, ATM CE/PE, FIXED ITM CE/PE and NIFTY FUT) has a price for this tick time. */
    public boolean isComplete() {
        return nifty != null && atmCe != null && atmPe != null
                && fixedItmCe != null && fixedItmPe != null && niftyFut != null;
    }
}

