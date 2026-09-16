package com.example.demo.service;

import com.example.demo.dto.OptionContract;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Selects the At-The-Money (ATM) Call option (CE) from a list of option
 * contracts belonging to the same expiry, given the current NIFTY spot price.
 */
@Service
public class NiftyOptionSelector {

    /**
     * Computes the ATM strike (nearest multiple of {@code strikeStep} to {@code spotPrice})
     * and returns the matching CE contract from {@code contracts}.
     */
    public OptionContract selectAtmCe(double spotPrice, List<OptionContract> contracts, int strikeStep) {
        return selectAtmByType(spotPrice, contracts, strikeStep, "CE");
    }

    /**
     * Computes the ATM strike (nearest multiple of {@code strikeStep} to {@code spotPrice})
     * and returns the matching PE contract from {@code contracts}.
     */
    public OptionContract selectAtmPe(double spotPrice, List<OptionContract> contracts, int strikeStep) {
        return selectAtmByType(spotPrice, contracts, strikeStep, "PE");
    }

    private OptionContract selectAtmByType(double spotPrice, List<OptionContract> contracts, int strikeStep, String optionType) {
        double atmStrike = Math.round(spotPrice / strikeStep) * (double) strikeStep;

        return contracts.stream()
                .filter(c -> optionType.equalsIgnoreCase(c.optionType()))
                .min(Comparator.comparingDouble(c -> Math.abs(c.strike() - atmStrike)))
                .orElseThrow(() -> new IllegalStateException(
                        "No ATM " + optionType + " contract found near strike " + atmStrike + " for spot " + spotPrice));
    }
}

