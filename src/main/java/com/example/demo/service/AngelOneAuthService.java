package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.util.TotpGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Handles authentication against Angel One's SmartAPI and caches the
 * resulting JWT so we don't log in on every price request.
 */
@Service
public class AngelOneAuthService {

    private static final Logger log = LoggerFactory.getLogger(AngelOneAuthService.class);
    private final RestClient restClient;
    private final AngelOneProperties properties;
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String jwtToken;
    private volatile String feedToken;
    private volatile long tokenIssuedAtMillis;
    // Angel One JWT tokens are valid for a while; refresh proactively every 5 hours.
    private static final long TOKEN_TTL_MILLIS = 5L * 60 * 60 * 1000;

    public AngelOneAuthService(RestClient.Builder restClientBuilder, AngelOneProperties properties) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    /** Returns a valid JWT bearer token, logging in again if necessary. */
    public String getJwtToken() {
        if (jwtToken != null && (System.currentTimeMillis() - tokenIssuedAtMillis) < TOKEN_TTL_MILLIS) {
            return jwtToken;
        }
        lock.lock();
        try {
            if (jwtToken != null && (System.currentTimeMillis() - tokenIssuedAtMillis) < TOKEN_TTL_MILLIS) {
                return jwtToken;
            }
            login();
            return jwtToken;
        } finally {
            lock.unlock();
        }
    }

    /** Returns a valid feed token (used to authenticate the SmartStream WebSocket), logging in again if necessary. */
    public String getFeedToken() {
        getJwtToken();
        return feedToken;
    }

    private void login() {
        String totp = TotpGenerator.now(properties.getTotpSecret());
        log.info("Attempting Angel One login: clientcode={}, totp={}, serverTime={}",
                properties.getClientCode(), totp, java.time.Instant.now());

        Map<String, String> body = Map.of(
                "clientcode", properties.getClientCode(),
                "password", properties.getPassword(),
                "totp", totp
        );

        JsonNode response;

        String rawBody;
        try {
            rawBody = restClient.post()
                    .uri("/rest/auth/angelbroking/user/v1/loginByPassword")
                    .headers(headers -> applyCommonHeaders(headers))
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Angel One login request failed: " + ex.getMessage(), ex);
        }

        try {
            response = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Angel One login returned a non-JSON response: " + rawBody, ex);
        }

        if (response == null || !response.path("status").asBoolean(false)) {
            String message = response != null ? response.path("message").asText("unknown error") : "no response";
            String errorCode = response != null ? response.path("errorcode").asText("") : "";
            throw new IllegalStateException("Angel One login failed: " + message + " (errorcode=" + errorCode + ")");
        }

        this.jwtToken = response.path("data").path("jwtToken").asText();
        this.feedToken = response.path("data").path("feedToken").asText();
        this.tokenIssuedAtMillis = System.currentTimeMillis();
    }

    /** Applies the mandatory Angel One SmartAPI headers to a request. */
    public void applyCommonHeaders(org.springframework.http.HttpHeaders headers) {
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        headers.set("X-ClientLocalIP", properties.getLocalIp());
        headers.set("X-ClientPublicIP", properties.getPublicIp());
        headers.set("X-MACAddress", properties.getMacAddress());
        headers.set("X-PrivateKey", properties.getApiKey());
    }

    public AngelOneProperties getProperties() {
        return properties;
    }
}


