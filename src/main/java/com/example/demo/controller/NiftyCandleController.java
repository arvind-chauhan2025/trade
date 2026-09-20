package com.example.demo.controller;

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
     * today), ordered chronologically. If {@code days} is omitted (or &lt;= 1), returns only today's candles. */
    @GetMapping("/api/nifty/candles/today")
    public List<NiftyCandle> getCandles(@RequestParam(name = "days", required = false) Integer days) {
        LocalDate today = LocalDate.now();
        if (days == null || days <= 1) {
            return niftyCandleService.getCandles(today);
        }
        LocalDate from = today.minusDays(days - 1L);
        return niftyCandleService.getCandles(from, today);
    }

    /** The current in-memory Support/Resistance zones, last recalculated after the most recent completed candle. */
    @GetMapping("/api/nifty/sr/current")
    public List<SrZone> getCurrentSrZones() {
        return supportResistanceService.getCurrentZones();
    }
}
