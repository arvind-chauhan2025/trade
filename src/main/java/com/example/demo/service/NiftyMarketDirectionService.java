package com.example.demo.service;

import com.example.demo.dto.NiftyCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Determines short-term NIFTY market structure for use alongside the existing divergence-based CE/PE
 * strategy. <b>This service is context only</b> — it never independently decides whether a trade should
 * be placed; divergence remains the primary entry signal (see {@link PaperTradingService}). Classifies
 * the current structure, over the latest {@code candleCount} <b>completed</b> 5-minute
 * {@code nifty_candle_5m} candles (default 4 -> ~20 minutes; the still-forming candle is never used), as:
 * <ul>
 *     <li><b>BULLISH</b> — Higher High + Higher Low.</li>
 *     <li><b>BEARISH</b> — Lower High + Lower Low.</li>
 *     <li><b>REVERSAL_CANDIDATE</b> — structure is moving against the previous trend (LH+HL = a
 *     possible bullish reversal in progress, HH+LL = a possible bearish reversal in progress). Useful
 *     because divergence can flip before a full HH+HL/LH+LL structure has re-formed.</li>
 *     <li><b>NEUTRAL</b> — insufficient, equal, or otherwise mixed structure.</li>
 * </ul>
 * <p>
 * A short (e.g. 4-candle) window alone can rarely contain two <i>confirmed</i> swing highs and two
 * confirmed swing lows, since a confirmed swing needs a candle on both sides. To avoid this collapsing to
 * NEUTRAL on almost every tick, swings are detected across every completed candle for the day, and the
 * two most recent confirmed swing highs/lows are used — preferring ones that fall inside the latest
 * {@code candleCount}-candle window, but borrowing the immediately preceding confirmed swing(s) as
 * context when the window itself doesn't contain enough.
 */
@Service
public class NiftyMarketDirectionService {

    private static final Logger log = LoggerFactory.getLogger(NiftyMarketDirectionService.class);

    private final NiftyCandleService niftyCandleService;

    public NiftyMarketDirectionService(NiftyCandleService niftyCandleService) {
        this.niftyCandleService = niftyCandleService;
    }

    public enum Direction {
        BULLISH, BEARISH, REVERSAL_CANDIDATE, NEUTRAL
    }

    public enum ReversalType {
        BULLISH_REVERSAL, BEARISH_REVERSAL, NONE
    }

    /** One confirmed swing high/low point. */
    public record SwingPoint(LocalTime intervalStart, double value) {
    }

    /** Result of one direction evaluation, with enough detail for logging/auditing and, via
     * {@link PaperTradingService}, persistence on the {@code paper_order} row. */
    public record MarketDirectionResult(
            Direction direction,
            int candlesUsed,
            int candlesAvailable,
            SwingPoint previousSwingHigh, SwingPoint lastSwingHigh,
            SwingPoint previousSwingLow, SwingPoint lastSwingLow,
            String highStructure, String lowStructure,
            ReversalType reversalType,
            String reason) {

        public boolean isBullish() {
            return direction == Direction.BULLISH;
        }

        public boolean isBearish() {
            return direction == Direction.BEARISH;
        }

        public boolean isReversalCandidate() {
            return direction == Direction.REVERSAL_CANDIDATE;
        }

        public boolean isNeutral() {
            return direction == Direction.NEUTRAL;
        }

        public boolean isBullishReversal() {
            return reversalType == ReversalType.BULLISH_REVERSAL;
        }

        public boolean isBearishReversal() {
            return reversalType == ReversalType.BEARISH_REVERSAL;
        }
    }

    /** Determines the current NIFTY market structure for {@code tradeDate} using a {@code candleCount}
     * (default 4 -> ~20-minute) latest-candle context window. Always logs the resulting direction and the
     * swing high/low values it was derived from. */
    public MarketDirectionResult determineDirection(LocalDate tradeDate, int candleCount) {
        if (candleCount < 3) {
            throw new IllegalArgumentException("candleCount must be at least 3");
        }

        List<NiftyCandle> completed = niftyCandleService.getCandles(tradeDate);
        if (completed == null || completed.isEmpty()) {
            log.info("Market direction: no completed 5m candles for {} -> NEUTRAL", tradeDate);
            return neutralResult(candleCount, 0, "No completed 5-minute candles");
        }

        // Defensive chronological ordering.
        completed = new ArrayList<>(completed);
        completed.sort(Comparator.comparing(NiftyCandle::intervalStart));

        if (completed.size() < candleCount) {
            log.info("Market direction: completed={} required={} -> NEUTRAL", completed.size(), candleCount);
            return neutralResult(candleCount, completed.size(), "Insufficient completed candles");
        }

        // Latest N completed candles = the short-term context window (default 4 x 5m = 20 minutes).
        List<NiftyCandle> directionWindow = completed.subList(completed.size() - candleCount, completed.size());

        // Swings are detected across every completed candle for the day (a swing needs a candle on both
        // sides, which a short window alone can rarely satisfy twice).
        List<SwingPoint> allSwingHighs = detectSwingHighs(completed);
        List<SwingPoint> allSwingLows = detectSwingLows(completed);

        List<SwingPoint> relevantHighs = getRelevantSwings(allSwingHighs, directionWindow, 2);
        List<SwingPoint> relevantLows = getRelevantSwings(allSwingLows, directionWindow, 2);

        if (relevantHighs.size() < 2 || relevantLows.size() < 2) {
            log.info("Market direction: window={} ({} -> {}), swingHighs={}, swingLows={} -> NEUTRAL",
                    candleCount, directionWindow.get(0).intervalStart(),
                    directionWindow.get(directionWindow.size() - 1).intervalStart(),
                    relevantHighs.size(), relevantLows.size());
            return new MarketDirectionResult(Direction.NEUTRAL, candleCount, completed.size(),
                    null, null, null, null, "UNCLEAR", "UNCLEAR", ReversalType.NONE,
                    "Insufficient confirmed swing structure");
        }

        SwingPoint previousHigh = relevantHighs.get(relevantHighs.size() - 2);
        SwingPoint lastHigh = relevantHighs.get(relevantHighs.size() - 1);
        SwingPoint previousLow = relevantLows.get(relevantLows.size() - 2);
        SwingPoint lastLow = relevantLows.get(relevantLows.size() - 1);

        boolean higherHigh = lastHigh.value() > previousHigh.value();
        boolean lowerHigh = lastHigh.value() < previousHigh.value();
        boolean higherLow = lastLow.value() > previousLow.value();
        boolean lowerLow = lastLow.value() < previousLow.value();

        String highStructure = higherHigh ? "HH" : lowerHigh ? "LH" : "EQUAL";
        String lowStructure = higherLow ? "HL" : lowerLow ? "LL" : "EQUAL";

        Direction direction;
        ReversalType reversalType;
        String reason;
        if (higherHigh && higherLow) {
            // Normal bullish structure.
            direction = Direction.BULLISH;
            reversalType = ReversalType.NONE;
            reason = "HH + HL";
        } else if (lowerHigh && lowerLow) {
            // Normal bearish structure.
            direction = Direction.BEARISH;
            reversalType = ReversalType.NONE;
            reason = "LH + LL";
        } else if (lowerHigh && higherLow) {
            // Bullish reversal candidate: market was making lower highs, but the latest low moved higher.
            // Bullish divergence can appear before a full HH + HL structure has re-formed.
            direction = Direction.REVERSAL_CANDIDATE;
            reversalType = ReversalType.BULLISH_REVERSAL;
            reason = "LH + HL: potential bullish reversal";
        } else if (higherHigh && lowerLow) {
            // Bearish reversal candidate: market was making higher highs, but the latest low moved lower.
            direction = Direction.REVERSAL_CANDIDATE;
            reversalType = ReversalType.BEARISH_REVERSAL;
            reason = "HH + LL: potential bearish reversal";
        } else {
            direction = Direction.NEUTRAL;
            reversalType = ReversalType.NONE;
            reason = "Mixed/unclear swing structure";
        }

        log.info("Market direction: window={} candles [{} -> {}], HIGH {}={} -> {}={}, LOW {}={} -> {}={}, "
                        + "structure={} {}, direction={}, reversalType={}, reason={}",
                candleCount, directionWindow.get(0).intervalStart(), directionWindow.get(directionWindow.size() - 1).intervalStart(),
                previousHigh.intervalStart(), previousHigh.value(), lastHigh.intervalStart(), lastHigh.value(),
                previousLow.intervalStart(), previousLow.value(), lastLow.intervalStart(), lastLow.value(),
                highStructure, lowStructure, direction, reversalType, reason);

        return new MarketDirectionResult(direction, candleCount, completed.size(),
                previousHigh, lastHigh, previousLow, lastLow, highStructure, lowStructure, reversalType, reason);
    }

    /** A candle is a confirmed swing high when its high exceeds both immediate neighbors' highs. The
     * first/last candle can never be a confirmed swing (no candle on one side). */
    private List<SwingPoint> detectSwingHighs(List<NiftyCandle> candles) {
        List<SwingPoint> swings = new ArrayList<>();
        for (int i = 1; i < candles.size() - 1; i++) {
            NiftyCandle previous = candles.get(i - 1);
            NiftyCandle current = candles.get(i);
            NiftyCandle next = candles.get(i + 1);
            if (current.high() > previous.high() && current.high() > next.high()) {
                swings.add(new SwingPoint(current.intervalStart(), current.high()));
            }
        }
        return swings;
    }

    /** A candle is a confirmed swing low when its low is below both immediate neighbors' lows. */
    private List<SwingPoint> detectSwingLows(List<NiftyCandle> candles) {
        List<SwingPoint> swings = new ArrayList<>();
        for (int i = 1; i < candles.size() - 1; i++) {
            NiftyCandle previous = candles.get(i - 1);
            NiftyCandle current = candles.get(i);
            NiftyCandle next = candles.get(i + 1);
            if (current.low() < previous.low() && current.low() < next.low()) {
                swings.add(new SwingPoint(current.intervalStart(), current.low()));
            }
        }
        return swings;
    }

    /** Prefers confirmed swings that fall physically inside the latest {@code directionWindow}; if that
     * window doesn't contain at least {@code required} of them, borrows the most recent confirmed swings
     * up to the end of the window (i.e. earlier history) as context, so the market doesn't degrade to
     * NEUTRAL simply because a short window contains too few local turning points. */
    private List<SwingPoint> getRelevantSwings(List<SwingPoint> allSwings, List<NiftyCandle> directionWindow, int required) {
        if (allSwings.isEmpty()) {
            return List.of();
        }

        LocalTime windowStart = directionWindow.get(0).intervalStart();
        LocalTime windowEnd = directionWindow.get(directionWindow.size() - 1).intervalStart();

        List<SwingPoint> insideWindow = allSwings.stream()
                .filter(s -> !s.intervalStart().isBefore(windowStart) && !s.intervalStart().isAfter(windowEnd))
                .toList();
        if (insideWindow.size() >= required) {
            return insideWindow;
        }

        List<SwingPoint> relevant = allSwings.stream()
                .filter(s -> !s.intervalStart().isAfter(windowEnd))
                .toList();
        if (relevant.size() <= required) {
            return relevant;
        }
        return relevant.subList(relevant.size() - required, relevant.size());
    }

    private MarketDirectionResult neutralResult(int candleCount, int candlesAvailable, String reason) {
        return new MarketDirectionResult(Direction.NEUTRAL, candleCount, candlesAvailable,
                null, null, null, null, "UNCLEAR", "UNCLEAR", ReversalType.NONE, reason);
    }
}
