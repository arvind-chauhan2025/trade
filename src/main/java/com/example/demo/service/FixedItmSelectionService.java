package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.OptionContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;

/** Pins instrument identities until expiry, including across single-instance restarts. */
@Service
public class FixedItmSelectionService {

    private final AngelOneProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public FixedItmSelectionService(AngelOneProperties properties) {
        this.properties = properties;
    }

    public record Selection(OptionContract ce, OptionContract pe) {
        public LocalDate expiry() {
            return ce.expiry();
        }
    }

    public synchronized Selection getOrSelect(List<OptionContract> contracts, double spot, LocalDate today) {
        Path path = Path.of(properties.getFixedItmStatePath()).toAbsolutePath();
        try {
            if (Files.exists(path)) {
                JsonNode saved = mapper.readTree(Files.readString(path));
                if (saved == null || saved.path("version").asInt() != 1) {
                    throw new IllegalStateException("Unsupported or empty fixed ITM state: " + path);
                }
                LocalDate expiry = LocalDate.parse(saved.path("expiry").asText());
                if (!expiry.isBefore(today)) {
                    return new Selection(restore(contracts, saved.path("ce"), expiry, "CE"),
                            restore(contracts, saved.path("pe"), expiry, "PE"));
                }
            }

            int step = properties.getStrikeStep();
            int depth = properties.getItmDepth();
            if (!Double.isFinite(spot) || spot <= 0 || step <= 0 || depth < 1) {
                throw new IllegalArgumentException("Spot and strike step must be positive; ITM depth must be >= 1");
            }
            LocalDate expiry = contracts.stream().map(OptionContract::expiry)
                    .filter(e -> !e.isBefore(today)).min(LocalDate::compareTo)
                    .orElseThrow(() -> new IllegalStateException("No upcoming NIFTY expiry"));
            double anchor = Math.round(spot / step) * (double) step;
            double distance = (double) depth * step;
            Selection selection = new Selection(find(contracts, expiry, anchor - distance, "CE"),
                    find(contracts, expiry, anchor + distance, "PE"));
            if (selection.ce().strike() >= spot || selection.pe().strike() <= spot) {
                throw new IllegalStateException("Selected strikes are not ITM relative to startup spot");
            }

            ObjectNode saved = mapper.createObjectNode();
            saved.put("version", 1);
            saved.put("expiry", expiry.toString());
            saved.put("initialAtmStrike", anchor);
            saved.put("strikeStep", step);
            saved.put("itmDepth", depth);
            saved.set("ce", identity(selection.ce()));
            saved.set("pe", identity(selection.pe()));
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), "fixed-itm-", ".tmp");
            try {
                Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(saved));
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return selection;
        } catch (IOException | java.time.DateTimeException ex) {
            throw new IllegalStateException("Cannot read/write fixed ITM state at " + path
                    + "; restore a valid state file or fix storage access. Selection was not silently reset.", ex);
        }
    }

    private OptionContract find(List<OptionContract> contracts, LocalDate expiry, double strike, String type) {
        return contracts.stream()
                .filter(c -> expiry.equals(c.expiry()) && type.equals(c.optionType()) && c.strike() == strike)
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Missing exact ITM " + type + " strike " + strike + " for expiry " + expiry));
    }

    private OptionContract restore(List<OptionContract> contracts, JsonNode saved, LocalDate expiry, String type) {
        return contracts.stream()
                .filter(c -> expiry.equals(c.expiry()) && type.equals(c.optionType())
                        && c.token().equals(saved.path("token").asText())
                        && c.symbol().equals(saved.path("symbol").asText())
                        && c.strike() == saved.path("strike").asDouble(Double.NaN))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Saved fixed ITM " + type + " is missing or changed in Scrip Master for " + expiry));
    }

    private ObjectNode identity(OptionContract contract) {
        ObjectNode node = mapper.createObjectNode();
        node.put("token", contract.token());
        node.put("symbol", contract.symbol());
        node.put("strike", contract.strike());
        return node;
    }
}
