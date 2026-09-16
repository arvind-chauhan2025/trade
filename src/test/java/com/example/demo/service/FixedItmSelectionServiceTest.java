package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.OptionContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FixedItmSelectionServiceTest {
    @TempDir Path directory;
    private final LocalDate expiry = LocalDate.of(2026, 9, 15);

    private AngelOneProperties properties() {
        AngelOneProperties properties = new AngelOneProperties();
        properties.setFixedItmStatePath(directory.resolve("fixed.json").toString());
        return properties;
    }

    private OptionContract contract(LocalDate date, double strike, String type) {
        String identity = date + "-" + strike + "-" + type;
        return new OptionContract(identity, "NIFTY-" + identity, "NIFTY", "NFO", "OPTIDX",
                date, strike, type, 1);
    }

    private List<OptionContract> contracts(LocalDate date) {
        return List.of(contract(date, 25350, "CE"), contract(date, 25450, "PE"),
                contract(date, 25300, "CE"), contract(date, 25500, "PE"));
    }

    @Test
    void selectsExactItmStrikesAndKeepsIdentitiesAcrossRestartAndSpotChange() {
        AngelOneProperties properties = properties();
        var first = new FixedItmSelectionService(properties)
                .getOrSelect(contracts(expiry), 25412.30, expiry.minusDays(3));
        assertEquals(25350, first.ce().strike());
        assertEquals(25450, first.pe().strike());
        properties.setItmDepth(2);
        var restored = new FixedItmSelectionService(properties)
                .getOrSelect(contracts(expiry), 25700, expiry);
        assertEquals(first, restored);
    }

    @Test
    void respectsConfiguredDepthForNewSelection() {
        AngelOneProperties properties = properties();
        properties.setItmDepth(2);
        var pair = new FixedItmSelectionService(properties)
                .getOrSelect(contracts(expiry), 25412.30, expiry.minusDays(1));
        assertEquals(25300, pair.ce().strike());
        assertEquals(25500, pair.pe().strike());
    }

    @Test
    void rollsToNextListedExpiryOnlyAfterExpiryDay() {
        var service = new FixedItmSelectionService(properties());
        LocalDate next = expiry.plusDays(7);
        var all = Stream.concat(contracts(expiry).stream(), contracts(next).stream()).toList();
        assertEquals(expiry, service.getOrSelect(all, 25412.30, expiry).expiry());
        assertEquals(next, service.getOrSelect(all, 25412.30, expiry.plusDays(1)).expiry());
    }

    @Test
    void rejectsMissingExactStrikeWithoutWritingState() {
        AngelOneProperties properties = properties();
        assertThrows(IllegalStateException.class, () -> new FixedItmSelectionService(properties)
                .getOrSelect(List.of(contract(expiry, 25300, "CE")), 25412.30, expiry));
        assertFalse(Files.exists(Path.of(properties.getFixedItmStatePath())));
    }

    @Test
    void rejectsMissingSavedContractInsteadOfReselecting() {
        var service = new FixedItmSelectionService(properties());
        service.getOrSelect(contracts(expiry), 25412.30, expiry);
        assertThrows(IllegalStateException.class, () -> service.getOrSelect(
                List.of(contract(expiry, 25300, "CE"), contract(expiry, 25500, "PE")), 25412.30, expiry));
    }

    @Test
    void rejectsCorruptStateWithoutOverwritingIt() throws Exception {
        AngelOneProperties properties = properties();
        Path state = Path.of(properties.getFixedItmStatePath());
        Files.writeString(state, "not json");
        assertThrows(IllegalStateException.class, () -> new FixedItmSelectionService(properties)
                .getOrSelect(contracts(expiry), 25412.30, expiry));
        assertEquals("not json", Files.readString(state));
    }

    @Test
    void rejectsInvalidDepthAndSpot() {
        AngelOneProperties properties = properties();
        var service = new FixedItmSelectionService(properties);
        assertThrows(IllegalArgumentException.class,
                () -> service.getOrSelect(contracts(expiry), Double.NaN, expiry));
        properties.setItmDepth(0);
        assertThrows(IllegalArgumentException.class,
                () -> service.getOrSelect(contracts(expiry), 25412.30, expiry));
    }
}
