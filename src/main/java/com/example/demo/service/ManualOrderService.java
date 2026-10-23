package com.example.demo.service;

import com.example.demo.dto.OptionGreek;
import com.example.demo.dto.TickSnapshot;
import com.example.demo.service.ManualOrderPersistenceService.ManualOrderSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Lets a user manually buy (open) and sell (exit) one CE/PE option at a time, picking either the
 * currently tracked ATM or Fixed ITM strike/option-type from {@link GreeksCacheService#getGreek(String)}
 * and pricing the fill off the latest live {@link TickSnapshot} (see
 * {@link TickPersistenceService#getLatestCompleteSnapshot()}). Every order is a plain long buy (buy to
 * open, sell to close); quantity is always {@code lots * LOT_SIZE}. Only one order may be open at a
 * time — place rejects while one is already open, and exit always targets that single open order.
 * <b>Never places a real Angel One order</b> — this only records the user's manual intent and its P&amp;L
 * against the live premium feed, persisted via {@link ManualOrderPersistenceService}.
 */
@Service
public class ManualOrderService {

    private static final Logger log = LoggerFactory.getLogger(ManualOrderService.class);

    /** NIFTY option lot size used to convert lots to quantity for every manual order. */
    static final int LOT_SIZE = 65;

    private final GreeksCacheService greeksCacheService;
    private final ManualOrderPersistenceService persistenceService;

    public ManualOrderService(GreeksCacheService greeksCacheService, ManualOrderPersistenceService persistenceService) {
        this.greeksCacheService = greeksCacheService;
        this.persistenceService = persistenceService;
    }

    /** Buys {@code lots} lots of the current ATM/ITM CE/PE contract at the live premium. Rejects (400) if
     * an order is already open, {@code moneyness}/{@code optionType} don't resolve to a known label,
     * {@code lots} isn't positive, no Greeks/strike has been cached yet for that label, or no live
     * premium is currently available for that exact strike. */
    public ManualOrderSummary placeOrder(String moneyness, String optionType, Integer lots) {
        if (lots == null || lots < 1) {
            throw badRequest("lots must be at least 1");
        }
        persistenceService.getOpenOrder().ifPresent(open -> {
            throw badRequest("An order is already open (id=" + open.id() + ", " + open.label()
                    + "); exit it before placing a new one");
        });

        String label = resolveLabel(moneyness, optionType);

        OptionGreek greek = greeksCacheService.getGreek(label);
        if (greek == null) {
            throw badRequest("No live strike/Greeks cached yet for " + label + "; try again shortly");
        }

        TickSnapshot snapshot = TickPersistenceService.getLatestCompleteSnapshot();
        if (snapshot == null) {
            throw badRequest("No live tick snapshot available yet");
        }

        Double snapshotStrike = strikeOf(label, snapshot);
        Double premium = premiumOf(label, snapshot);
        if (snapshotStrike == null || premium == null || Double.compare(snapshotStrike, greek.strike()) != 0) {
            throw badRequest("Live premium not yet available for " + label + " at strike " + greek.strike());
        }

        double entryValue = premium * LOT_SIZE * lots;
        long id = persistenceService.insertOpen(label, moneyness.toUpperCase(), optionTypeCode(optionType), lots,
                LOT_SIZE, greek.strike(), snapshot.tickTime(), premium, entryValue);
        log.info("Manual order placed: id={} label={} lots={} strike={} premium={}", id, label, lots, greek.strike(), premium);
        return persistenceService.getById(id).orElseThrow();
    }

    /** Sells (exits) the currently open order, identified by {@code orderId}, at the live premium for its
     * label, and records P&amp;L = {@code (exitPremium - entryPremium) * quantity}. Rejects (400) if no
     * order is open, {@code orderId} doesn't match the open order, or no live premium is currently
     * available. */
    public ManualOrderSummary exitOrder(long orderId) {
        ManualOrderSummary open = persistenceService.getOpenOrder()
                .orElseThrow(() -> badRequest("No open order to exit"));
        if (open.id() != orderId) {
            throw badRequest("Order " + orderId + " is not the currently open order (open order is id="
                    + open.id() + ")");
        }

        TickSnapshot snapshot = TickPersistenceService.getLatestCompleteSnapshot();
        if (snapshot == null) {
            throw badRequest("No live tick snapshot available yet");
        }
        Double premium = premiumOf(open.label(), snapshot);
        if (premium == null) {
            throw badRequest("Live premium not currently available for " + open.label());
        }

        double exitValue = premium * open.quantity();
        double pnl = (premium - open.entryPremium()) * open.quantity();
        persistenceService.closeOrder(orderId, snapshot.tickTime(), premium, exitValue, pnl);
        log.info("Manual order exited: id={} label={} exitPremium={} pnl={}", orderId, open.label(), premium, pnl);
        return persistenceService.getById(orderId).orElseThrow();
    }

    /** Returns the currently open manual order, if any. */
    public Optional<ManualOrderSummary> getOpenOrder() {
        return persistenceService.getOpenOrder();
    }

    /** Returns every manual order (open or closed) for the last {@code days} trading dates up to and
     * including today (default 1, i.e. today only). */
    public List<ManualOrderSummary> getOrders(Integer days) {
        LocalDate today = LocalDate.now();
        LocalDate from = (days == null || days <= 1) ? today : today.minusDays(days - 1L);
        return persistenceService.getOrders(from, today);
    }

    /** Maps {@code moneyness}/{@code optionType} to the tracked label keys used by
     * {@link GreeksCacheService} and {@link TickSnapshot} ("ATM CE", "ATM PE", "FIXED ITM CE",
     * "FIXED ITM PE"). Throws a 400 if either value isn't recognized. */
    private String resolveLabel(String moneyness, String optionType) {
        String prefix = switch (moneyness == null ? "" : moneyness.trim().toUpperCase()) {
            case "ATM" -> "ATM";
            case "ITM" -> "FIXED ITM";
            default -> throw badRequest("moneyness must be ATM or ITM, got: " + moneyness);
        };
        String suffix = optionTypeCode(optionType);
        return prefix + " " + suffix;
    }

    private String optionTypeCode(String optionType) {
        return switch (optionType == null ? "" : optionType.trim().toUpperCase()) {
            case "CALL", "CE" -> "CE";
            case "PUT", "PE" -> "PE";
            default -> throw badRequest("optionType must be CALL or PUT, got: " + optionType);
        };
    }

    private Double strikeOf(String label, TickSnapshot snapshot) {
        return switch (label) {
            case "ATM CE" -> snapshot.atmCeStrike();
            case "ATM PE" -> snapshot.atmPeStrike();
            case "FIXED ITM CE" -> snapshot.fixedItmCeStrike();
            case "FIXED ITM PE" -> snapshot.fixedItmPeStrike();
            default -> null;
        };
    }

    private Double premiumOf(String label, TickSnapshot snapshot) {
        return switch (label) {
            case "ATM CE" -> snapshot.atmCe();
            case "ATM PE" -> snapshot.atmPe();
            case "FIXED ITM CE" -> snapshot.fixedItmCe();
            case "FIXED ITM PE" -> snapshot.fixedItmPe();
            default -> null;
        };
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
