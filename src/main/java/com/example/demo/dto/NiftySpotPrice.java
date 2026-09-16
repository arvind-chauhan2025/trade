package com.example.demo.dto;

/** Simple DTO representing the NIFTY 50 spot (index) price. */
public record NiftySpotPrice(
        String tradingSymbol,
        String symbolToken,
        double ltp,
        double open,
        double high,
        double low,
        double close
) {
}

