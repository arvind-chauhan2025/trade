package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.OptionContract;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FixedItmSelectionServiceTest {
    private final LocalDate expiry = LocalDate.of(2026, 9, 15);

    private AngelOneProperties properties() {
        return new AngelOneProperties();
    }

    private OptionContract contract(LocalDate date, double strike, String type) {
        String identity = date + "-" + strike + "-" + type;
        return new OptionContract(identity, "NIFTY-" + identity, "NIFTY", "NFO", "OPTIDX",
                date, strike, type, 1);
    }

    private List<OptionContract> contracts(LocalDate date) {
        return List.of(contract(date, 25350, "CE"), contract(date, 25450, "PE"),
                contract(date, 25300, "CE"), contract(date, 25500, "PE"),
                contract(date, 25650, "CE"), contract(date, 25750, "PE"));
    }

    @Test
    void selectsExactItmStrikesForCurrentSpot() {
        AngelOneProperties properties = properties();
        var selection = new FixedItmSelectionService(properties)
                .select(contracts(expiry), 25412.30, expiry.minusDays(3));
        assertEquals(25350, selection.ce().strike());
        assertEquals(25450, selection.pe().strike());
    }

    @Test
    void recomputesFreshEachCallInsteadOfPinningPriorSelection() {
        AngelOneProperties properties = properties();
        var service = new FixedItmSelectionService(properties);
        var first = service.select(contracts(expiry), 25412.30, expiry.minusDays(3));
        assertEquals(25350, first.ce().strike());

        // Spot has moved into the next 30-min/day window: selection must track it, not stay pinned.
        var second = service.select(contracts(expiry), 25700, expiry);
        assertEquals(25650, second.ce().strike());
        assertEquals(25750, second.pe().strike());
    }

    @Test
    void respectsConfiguredDepthForNewSelection() {
        AngelOneProperties properties = properties();
        properties.setItmDepth(2);
        var pair = new FixedItmSelectionService(properties)
                .select(contracts(expiry), 25412.30, expiry.minusDays(1));
        assertEquals(25300, pair.ce().strike());
        assertEquals(25500, pair.pe().strike());
    }

    @Test
    void rollsToNextListedExpiryOnlyAfterExpiryDay() {
        var service = new FixedItmSelectionService(properties());
        LocalDate next = expiry.plusDays(7);
        var all = Stream.concat(contracts(expiry).stream(), contracts(next).stream()).toList();
        assertEquals(expiry, service.select(all, 25412.30, expiry).expiry());
        assertEquals(next, service.select(all, 25412.30, expiry.plusDays(1)).expiry());
    }

    @Test
    void rejectsMissingExactStrike() {
        AngelOneProperties properties = properties();
        assertThrows(IllegalStateException.class, () -> new FixedItmSelectionService(properties)
                .select(List.of(contract(expiry, 25300, "CE")), 25412.30, expiry));
    }

    @Test
    void rejectsInvalidDepthAndSpot() {
        AngelOneProperties properties = properties();
        var service = new FixedItmSelectionService(properties);
        assertThrows(IllegalArgumentException.class,
                () -> service.select(contracts(expiry), Double.NaN, expiry));
        properties.setItmDepth(0);
        assertThrows(IllegalArgumentException.class,
                () -> service.select(contracts(expiry), 25412.30, expiry));
    }
}
