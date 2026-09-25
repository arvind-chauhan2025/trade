package com.example.demo.dto;

import java.time.LocalDate;

/** A {@link TickSnapshot} tagged with the trade date it belongs to, used when returning snapshots that
 * may span more than one trading day (e.g. filtering by a number of past days or by a date/time range). */
public record DatedTickSnapshot(LocalDate tickDate, TickSnapshot snapshot) {
}
