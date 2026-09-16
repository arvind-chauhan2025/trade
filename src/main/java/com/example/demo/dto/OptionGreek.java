package com.example.demo.dto;

/**
 * A single option contract's Greeks, as returned by Angel One's Option Greek API,
 * for one strike/optionType at a given expiry.
 */
public record OptionGreek(
        double strike,
        String optionType,
        double delta,
        double gamma,
        double theta,
        double vega,
        double impliedVolatility
) {
}

