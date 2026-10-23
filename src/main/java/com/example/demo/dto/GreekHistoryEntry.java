package com.example.demo.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A persisted {@link OptionGreek} snapshot for one tracked label (ATM CE, ATM PE, FIXED ITM CE,
 * FIXED ITM PE), tagged with the trade date and the instant it was captured at. One row is appended per
 * {@link com.example.demo.service.GreeksCacheService} refresh, so this gives a historical log of how a
 * label's Greeks evolved through the day rather than just the latest cached value. */
public record GreekHistoryEntry(LocalDate tickDate, String tickTime, String label, LocalDateTime capturedAt, OptionGreek greek) {
}
