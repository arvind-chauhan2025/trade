package com.example.demo.dto;

import java.util.List;

/** Combined response for the candles endpoint: the requested candles plus the Support/Resistance zones
 * dynamically calculated from exactly those candles. */
public record CandlesWithZones(List<NiftyCandle> candles, List<SrZone> srZones) {
}
