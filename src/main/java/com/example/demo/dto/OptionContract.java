package com.example.demo.dto;

import java.time.LocalDate;

/**
 * Represents a single NIFTY option contract (CE or PE) parsed from the
 * Angel One Scrip Master instrument dump.
 */
public record OptionContract(
        String token,
        String symbol,
        String name,
        String exchSeg,
        String instrumentType,
        LocalDate expiry,
        double strike,
        String optionType,
        int lotSize
) {
}

