package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.NiftySpotPrice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Fetches the live NIFTY 50 index spot price from Angel One's SmartAPI
 * Market Quote endpoint.
 */
@Service
public class NiftySpotPriceService {

    private final RestClient restClient;
    private final AngelOneAuthService authService;
    private final AngelOneProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NiftySpotPriceService(RestClient.Builder restClientBuilder,
                                  AngelOneAuthService authService,
                                  AngelOneProperties properties) {
        this.authService = authService;
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    /** Fetches the current NIFTY 50 spot price. */
    public NiftySpotPrice getNiftySpotPrice() {
        String jwt = authService.getJwtToken();

        Map<String, Object> body = Map.of(
                "mode", "FULL",
                "exchangeTokens", Map.of("NSE", List.of(properties.getNiftyQuoteToken()))
        );

        JsonNode response;
        String rawBody;
        try {
            rawBody = restClient.post()
                    .uri("/rest/secure/angelbroking/market/v1/quote/")
                    .headers(headers -> {
                        authService.applyCommonHeaders(headers);
                        headers.setBearerAuth(jwt);
                    })
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Angel One quote request failed: " + ex.getMessage(), ex);
        }

        try {
            response = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            throw new IllegalStateException("Angel One quote returned a non-JSON response: " + rawBody, ex);
        }

        if (response == null || !response.path("status").asBoolean(false)) {
            String message = response != null ? response.path("message").asText("unknown error") : "no response";
            String errorCode = response != null ? response.path("errorcode").asText("") : "";
            throw new IllegalStateException("Failed to fetch NIFTY spot price: " + message + " (errorcode=" + errorCode + ")");
        }

        JsonNode fetched = response.path("data").path("fetched");
        if (!fetched.isArray() || fetched.isEmpty()) {
            throw new IllegalStateException("Angel One returned no quote data for NIFTY");
        }

        JsonNode quote = fetched.get(0);
        return new NiftySpotPrice(
                quote.path("tradingSymbol").asText(),
                quote.path("symbolToken").asText(),
                quote.path("ltp").asDouble(),
                quote.path("open").asDouble(),
                quote.path("high").asDouble(),
                quote.path("low").asDouble(),
                quote.path("close").asDouble()
        );
    }
}

