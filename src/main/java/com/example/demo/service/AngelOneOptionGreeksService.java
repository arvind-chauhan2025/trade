package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.OptionGreek;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls Angel One's Option Greek API (Market Data) to fetch Delta/Gamma/Theta/
 * Vega/IV for every NIFTY option contract at a given expiry.
 */
@Service
public class AngelOneOptionGreeksService {

    private static final Logger log = LoggerFactory.getLogger(AngelOneOptionGreeksService.class);
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH);

    private final RestClient restClient;
    private final AngelOneAuthService authService;
    private final AngelOneProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AngelOneOptionGreeksService(RestClient.Builder restClientBuilder,
                                        AngelOneAuthService authService,
                                        AngelOneProperties properties) {
        this.authService = authService;
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    /** Fetches Greeks for every NIFTY option contract at the given expiry. */
    public List<OptionGreek> getGreeks(LocalDate expiry) {
        String expiryText = expiry.format(EXPIRY_FORMAT).toUpperCase(Locale.ENGLISH);
        Map<String, String> body = Map.of("name", "NIFTY", "expirydate", expiryText);

        String rawBody;
        try {
            rawBody = restClient.post()
                    .uri("/rest/secure/angelbroking/marketData/v1/optionGreek")
                    .headers(headers -> {
                        authService.applyCommonHeaders(headers);
                        headers.setBearerAuth(authService.getJwtToken());
                    })
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Angel One Option Greeks request failed: " + ex.getMessage(), ex);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            throw new IllegalStateException("Angel One Option Greeks returned a non-JSON response: " + rawBody, ex);
        }

        if (root == null || !root.path("status").asBoolean(false)) {
            String message = root != null ? root.path("message").asText("unknown error") : "no response";
            String errorCode = root != null ? root.path("errorcode").asText("") : "";
            throw new IllegalStateException("Angel One Option Greeks failed: " + message + " (errorcode=" + errorCode + ")");
        }

        List<OptionGreek> greeks = new ArrayList<>();
        for (JsonNode node : root.path("data")) {
            double strike;
            try {
                strike = Double.parseDouble(node.path("strikePrice").asText("0"));
            } catch (NumberFormatException ex) {
                continue;
            }
            String optionType = node.path("optionType").asText("");
            if (optionType.isBlank()) {
                continue;
            }
            greeks.add(new OptionGreek(
                    strike,
                    optionType,
                    node.path("delta").asDouble(Double.NaN),
                    node.path("gamma").asDouble(Double.NaN),
                    node.path("theta").asDouble(Double.NaN),
                    node.path("vega").asDouble(Double.NaN),
                    node.path("impliedVolatility").asDouble(Double.NaN)
            ));
        }

        if (greeks.isEmpty()) {
            throw new IllegalStateException("Angel One Option Greeks returned no contracts for expiry " + expiryText);
        }

        log.info("Fetched {} option Greeks entries for NIFTY expiry {}", greeks.size(), expiryText);
        return greeks;
    }
}

