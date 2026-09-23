package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.OptionContract;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Selects the "ITM" (in-the-money) CE/PE pair — a CE strike below spot and a PE strike above spot,
 * each offset from the ATM strike by a configurable depth — for a given NIFTY spot price.
 * <p>
 * Selections are computed fresh on every call; they are <b>not</b> pinned across restarts or across the
 * trading day. {@link TradingApplication} calls {@link #select} once at market open (so each day starts
 * from that morning's own spot, never a stale prior-day anchor) and again every 30 minutes thereafter as
 * part of the rolling ATM/ITM re-selection, so the pair always reflects the spot at the start of the
 * current 30-minute window and is held fixed for the rest of that window.
 */
@Service
public class FixedItmSelectionService {

    private final AngelOneProperties properties;

    public FixedItmSelectionService(AngelOneProperties properties) {
        this.properties = properties;
    }

    public record Selection(OptionContract ce, OptionContract pe) {
        public LocalDate expiry() {
            return ce.expiry();
        }
    }

    /**
     * Computes the ITM CE/PE pair for {@code spot} from {@code contracts}, using the nearest upcoming
     * expiry (the earliest expiry that is not before {@code today}). The ATM anchor is {@code spot}
     * rounded to the nearest {@code angelone.strike-step}; the CE leg sits {@code angelone.itm-depth}
     * steps below that anchor, the PE leg the same number of steps above it.
     */
    public Selection select(List<OptionContract> contracts, double spot, LocalDate today) {
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
            throw new IllegalStateException("Selected strikes are not ITM relative to current spot");
        }
        return selection;
    }

    private OptionContract find(List<OptionContract> contracts, LocalDate expiry, double strike, String type) {
        return contracts.stream()
                .filter(c -> expiry.equals(c.expiry()) && type.equals(c.optionType()) && c.strike() == strike)
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Missing exact ITM " + type + " strike " + strike + " for expiry " + expiry));
    }
}
