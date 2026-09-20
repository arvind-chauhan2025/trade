package com.example.demo.controller;

import com.example.demo.dto.CandlesWithZones;
import com.example.demo.dto.NiftyCandle;
import com.example.demo.dto.SrZone;
import com.example.demo.service.NiftyCandleService;
import com.example.demo.service.SupportResistanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Read-only endpoints for inspecting today's 5-minute NIFTY candles and the current Support/Resistance zones. */
@RestController
public class NiftyCandleController {

    private final NiftyCandleService niftyCandleService;
    private final SupportResistanceService supportResistanceService;

    public NiftyCandleController(NiftyCandleService niftyCandleService, SupportResistanceService supportResistanceService) {
        this.niftyCandleService = niftyCandleService;
        this.supportResistanceService = supportResistanceService;
    }

    /** Every completed 5-minute NIFTY candle persisted for the last {@code days} trade dates (inclusive of
     * today), along with the active Support/Resistance zones (nearest 2 above and 2 below the current
     * spot) dynamically recalculated from exactly those candles. If {@code days} is omitted (or &lt;= 1),
     * returns only today's candles/zones. If {@code spot} is omitted, the last returned candle's close is
     * used as the current spot price for the S/R filtering. */
    @GetMapping("/api/nifty/candles/today")
    public CandlesWithZones getCandles(@RequestParam(name = "days", required = false) Integer days,
                                        @RequestParam(name = "spot") Double spot) {
        LocalDate today = LocalDate.now();
        List<NiftyCandle> candles = (days == null || days <= 1)
                ? niftyCandleService.getCandles(today)
                : niftyCandleService.getCandles(today.minusDays(days - 1L), today);
        double currentSpot = spot != null ? spot
                : candles.isEmpty() ? 0.0 : candles.get(candles.size() - 1).close();
        List<SrZone> zones = supportResistanceService.calculateZonesFor(candles, currentSpot);
        return new CandlesWithZones(candles, zones);
    }

    /** The current in-memory Support/Resistance zones, last recalculated after the most recent completed candle. */
    @GetMapping("/api/nifty/sr/current")
    public List<SrZone> getCurrentSrZones() {
        return supportResistanceService.getCurrentZones();
    }
}
