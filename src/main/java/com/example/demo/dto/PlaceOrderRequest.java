package com.example.demo.dto;

/**
 * Request body for {@code POST /api/manual-orders/place}: which contract to buy and how many lots.
 *
 * @param moneyness  {@code "ATM"} or {@code "ITM"} (case-insensitive) — selects the currently tracked
 *                   ATM or Fixed ITM strike from {@code GreeksCacheService}.
 * @param optionType {@code "CALL"} or {@code "PUT"} (case-insensitive).
 * @param lots       number of lots to buy; must be at least 1. Actual quantity = {@code lots * 65}.
 */
public record PlaceOrderRequest(String moneyness, String optionType, Integer lots) {
}
