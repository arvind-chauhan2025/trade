package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.OptionContract;
import com.example.demo.dto.TickRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Connects to Angel One's SmartStream WebSocket and streams live LTP (Last
 * Traded Price) ticks for the NIFTY 50 index and the dynamically selected
 * ATM CE/PE and fixed weekly ITM CE/PE option contracts.
 * <p>
 * Implements the publicly documented SmartStream v2 binary tick protocol
 * directly over {@link WebSocket} (the official smartapi-java SDK artifact
 * could not be resolved from Maven Central/JitPack in this environment, so
 * the wire protocol is implemented here instead).
 */
@Service
public class AngelOneMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(AngelOneMarketDataService.class);
    private static final URI SMART_STREAM_URI = URI.create("wss://smartapisocket.angelone.in/smart-stream");

    private static final int EXCHANGE_TYPE_NSE_CM = 1;
    private static final int EXCHANGE_TYPE_NSE_FO = 2;
    private static final int MODE_LTP = 1;
    private static final int LTP_PACKET_LENGTH = 51;
    // NSE/BSE exchange timestamps only have second-level granularity, so we
    // format without milliseconds (they would always render as .000 anyway).
    private static final DateTimeFormatter TICK_TIME_FORMAT = DateTimeFormatter
            .ofPattern("HH:mm:ss")
            .withZone(ZoneId.of("Asia/Kolkata"));

    private final AngelOneProperties properties;
    private final TickPersistenceService tickPersistenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "smartstream-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket webSocket;
    private volatile Map<String, String> optionTokensByLabel = Map.of();
    private volatile Map<String, OptionContract> optionContractsByLabel = Map.of();

    public AngelOneMarketDataService(AngelOneProperties properties, TickPersistenceService tickPersistenceService) {
        this.properties = properties;
        this.tickPersistenceService = tickPersistenceService;
    }

    /** Subscribes to NIFTY spot, startup ATM CE/PE, the pinned weekly ITM CE/PE, and the nearest NIFTY future. */
    public void start(String jwtToken, String feedToken, OptionContract atmCe, OptionContract atmPe,
                      OptionContract fixedItmCe, OptionContract fixedItmPe, OptionContract niftyFut) {
        this.optionTokensByLabel = Map.of(
                "ATM CE", atmCe.token(), "ATM PE", atmPe.token(),
                "FIXED ITM CE", fixedItmCe.token(), "FIXED ITM PE", fixedItmPe.token(),
                "NIFTY FUT", niftyFut.token());

        HttpClient httpClient = HttpClient.newHttpClient();
        TickListener listener = new TickListener();

        CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + jwtToken)
                .header("x-api-key", properties.getApiKey())
                .header("x-client-code", properties.getClientCode())
                .header("x-feed-token", feedToken)
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(SMART_STREAM_URI, listener);

        future.whenComplete((ws, error) -> {
            if (error != null) {
                log.error("SmartStream WebSocket connection failed", error);
                return;
            }
            this.webSocket = ws;
            subscribe(ws);
            heartbeatExecutor.scheduleAtFixedRate(() -> sendHeartbeat(ws), 20, 20, TimeUnit.SECONDS);
        });
    }

    /** Stops the heartbeat scheduler and closes the WebSocket connection, if open. */
    public void stop() {
        heartbeatExecutor.shutdownNow();
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client shutdown");
        }
    }

    private void subscribe(WebSocket ws) {
        try {
            Map<String, Object> request = Map.of(
                    "correlationID", "nifty-atm-stream",
                    "action", 1,
                    "params", Map.of(
                            "mode", MODE_LTP,
                            "tokenList", List.of(
                                    Map.of("exchangeType", EXCHANGE_TYPE_NSE_CM, "tokens", List.of(properties.getNiftyToken())),
                                    Map.of("exchangeType", EXCHANGE_TYPE_NSE_FO, "tokens",
                                            optionTokensByLabel.values().stream().distinct().sorted().toList())
                            )
                    )
            );
            String json = objectMapper.writeValueAsString(request);
            ws.sendText(json, true);
            log.info("Sent SmartStream subscription request: {}", json);
        } catch (Exception ex) {
            log.error("Failed to send SmartStream subscription request", ex);
        }
    }

    private void sendHeartbeat(WebSocket ws) {
        try {
            ws.sendText("ping", true);
        } catch (Exception ex) {
            log.warn("Failed to send SmartStream heartbeat", ex);
        }
    }

    /** WebSocket listener that accumulates binary frames and decodes SmartStream LTP tick packets. */
    private class TickListener implements WebSocket.Listener {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("SmartStream WebSocket connected");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            buffer.writeBytes(chunk);
            webSocket.request(1);

            if (last) {
                byte[] packet = buffer.toByteArray();
                buffer.reset();
                try {
                    processTick(packet);
                } catch (Exception ex) {
                    log.warn("Failed to decode SmartStream tick packet", ex);
                }
            }
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            log.debug("SmartStream text message: {}", data);
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("SmartStream WebSocket error", error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("SmartStream WebSocket closed: status={} reason={}", statusCode, reason);
            return null;
        }
    }

    private void processTick(byte[] packet) {
        if (packet.length < LTP_PACKET_LENGTH) {
            log.warn("Ignoring undersized SmartStream packet of length {}", packet.length);
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN);

        int subscriptionMode = buf.get(0) & 0xFF;
        int exchangeType = buf.get(1) & 0xFF;

        byte[] tokenBytes = new byte[25];
        buf.get(2, tokenBytes);
        String token = new String(tokenBytes, StandardCharsets.US_ASCII).trim().replace("\u0000", "");

        // Exchange timestamp is epoch millis, but NSE/BSE only report whole-second
        // precision, so the millisecond component is always 0 — this is expected.
        long exchangeTimestampMillis = buf.getLong(35);
        String tickTime = exchangeTimestampMillis > 0
                ? TICK_TIME_FORMAT.format(Instant.ofEpochMilli(exchangeTimestampMillis))
                : "n/a";

        long lastTradedPrice = buf.getLong(43);
        double price = lastTradedPrice / 100.0;

        if (subscriptionMode != MODE_LTP) {
            return;
        }

        if (exchangeType == EXCHANGE_TYPE_NSE_CM && token.equals(properties.getNiftyToken())) {
            log.info("NIFTY = {} @ {}", price, tickTime);
            tickPersistenceService.offer(new TickRecord("NIFTY", token, price, exchangeTimestampMillis, tickTime));
        } else if (exchangeType == EXCHANGE_TYPE_NSE_FO) {
            optionTokensByLabel.forEach((label, selectedToken) -> {
                if (token.equals(selectedToken)) {
                    log.info("{} = {} @ {}", label, price, tickTime);
                    tickPersistenceService.offer(new TickRecord(label, token, price, exchangeTimestampMillis, tickTime));
                }
            });
        }
    }
}

