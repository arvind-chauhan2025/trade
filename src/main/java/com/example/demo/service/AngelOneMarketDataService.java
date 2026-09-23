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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    // Reconnect backoff: starts at 2s, doubles on each consecutive failure, capped at 30s.
    private static final long INITIAL_RECONNECT_DELAY_SECONDS = 2;
    private static final long MAX_RECONNECT_DELAY_SECONDS = 30;

    private final AngelOneProperties properties;
    private final TickPersistenceService tickPersistenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    // Prevents onError + onClose (both fired for the same drop) from scheduling two parallel reconnects.
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);

    // Recreated on each start() (not final) since stop() shuts these down; the pipeline is
    // stopped/started daily (market close/open), so a permanently-shutdown executor would
    // silently break heartbeats/reconnects on the next session.
    private volatile ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledExecutorService reconnectExecutor;

    private volatile WebSocket webSocket;
    private volatile Map<String, String> optionTokensByLabel = Map.of();
    private volatile Map<String, OptionContract> optionContractsByLabel = Map.of();
    private volatile ScheduledFuture<?> heartbeatTask;
    // Held so a dropped connection can be transparently re-established with the same
    // credentials/subscriptions; cleared (stopRequested=true) on an explicit stop().
    private volatile boolean stopRequested = false;
    private volatile String jwtToken;
    private volatile String feedToken;
    private volatile OptionContract atmCe;
    private volatile OptionContract atmPe;
    private volatile OptionContract fixedItmCe;
    private volatile OptionContract fixedItmPe;
    private volatile OptionContract niftyFut;

    public AngelOneMarketDataService(AngelOneProperties properties, TickPersistenceService tickPersistenceService) {
        this.properties = properties;
        this.tickPersistenceService = tickPersistenceService;
    }

    /** Subscribes to NIFTY spot, startup ATM CE/PE, the pinned weekly ITM CE/PE, and the nearest NIFTY future. */
    public void start(String jwtToken, String feedToken, OptionContract atmCe, OptionContract atmPe,
                      OptionContract fixedItmCe, OptionContract fixedItmPe, OptionContract niftyFut) {
        this.jwtToken = jwtToken;
        this.feedToken = feedToken;
        this.atmCe = atmCe;
        this.atmPe = atmPe;
        this.fixedItmCe = fixedItmCe;
        this.fixedItmPe = fixedItmPe;
        this.niftyFut = niftyFut;
        this.optionTokensByLabel = Map.of(
                "ATM CE", atmCe.token(), "ATM PE", atmPe.token(),
                "FIXED ITM CE", fixedItmCe.token(), "FIXED ITM PE", fixedItmPe.token(),
                "NIFTY FUT", niftyFut.token());
        this.optionContractsByLabel = Map.of(
                "ATM CE", atmCe, "ATM PE", atmPe,
                "FIXED ITM CE", fixedItmCe, "FIXED ITM PE", fixedItmPe,
                "NIFTY FUT", niftyFut);

        this.stopRequested = false;
        reconnectAttempts.set(0);
        reconnectPending.set(false);
        this.heartbeatExecutor = newDaemonScheduler("smartstream-heartbeat");
        this.reconnectExecutor = newDaemonScheduler("smartstream-reconnect");
        connect();
    }

    private static ScheduledExecutorService newDaemonScheduler(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    /** Establishes (or re-establishes) the SmartStream WebSocket using the currently held credentials/subscriptions. */
    private void connect() {
        if (stopRequested) {
            return;
        }
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
                scheduleReconnect();
                return;
            }
            this.webSocket = ws;
            reconnectAttempts.set(0);
            subscribe(ws);
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
            }
            heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> sendHeartbeat(ws), 20, 20, TimeUnit.SECONDS);
        });
    }

    /** Schedules a reconnect attempt with exponential backoff (2s, 4s, 8s, ... capped at 30s), unless stopped. */
    private void scheduleReconnect() {
        if (stopRequested || !reconnectPending.compareAndSet(false, true)) {
            return;
        }
        int attempt = reconnectAttempts.getAndIncrement();
        long delaySeconds = Math.min(INITIAL_RECONNECT_DELAY_SECONDS << Math.min(attempt, 10), MAX_RECONNECT_DELAY_SECONDS);
        log.warn("Scheduling SmartStream reconnect attempt {} in {}s", attempt + 1, delaySeconds);
        reconnectExecutor.schedule(() -> {
            reconnectPending.set(false);
            connect();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /** Stops the heartbeat/reconnect schedulers and closes the WebSocket connection, if open. */
    public void stop() {
        stopRequested = true;
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
        }
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

    /**
     * Atomically swaps the ATM CE/PE and FIXED ITM CE/PE contracts for the current session, used by the
     * 30-minute rolling ATM/ITM re-selection ({@code TradingApplication}): unsubscribes any NSE_FO tokens
     * no longer needed, subscribes any newly needed ones, and updates the label→token/contract maps used
     * to route incoming ticks so subsequent ticks are attributed to the new strikes. NIFTY spot and NIFTY
     * FUT subscriptions are left untouched. No-op (logs a warning) if the WebSocket isn't connected yet —
     * the caller should retry on the next window rather than silently losing the re-selection.
     */
    public synchronized void updateSelection(OptionContract atmCe, OptionContract atmPe,
                                              OptionContract fixedItmCe, OptionContract fixedItmPe) {
        WebSocket ws = this.webSocket;
        if (ws == null) {
            log.warn("Cannot update ATM/ITM selection: SmartStream WebSocket not connected");
            return;
        }

        Map<String, String> newTokensByLabel = Map.of(
                "ATM CE", atmCe.token(), "ATM PE", atmPe.token(),
                "FIXED ITM CE", fixedItmCe.token(), "FIXED ITM PE", fixedItmPe.token(),
                "NIFTY FUT", niftyFut.token());
        Map<String, OptionContract> newContractsByLabel = Map.of(
                "ATM CE", atmCe, "ATM PE", atmPe,
                "FIXED ITM CE", fixedItmCe, "FIXED ITM PE", fixedItmPe,
                "NIFTY FUT", niftyFut);

        java.util.Set<String> oldTokens = new java.util.HashSet<>(this.optionTokensByLabel.values());
        java.util.Set<String> newTokens = new java.util.HashSet<>(newTokensByLabel.values());
        List<String> toSubscribe = newTokens.stream().filter(t -> !oldTokens.contains(t)).sorted().toList();
        List<String> toUnsubscribe = oldTokens.stream().filter(t -> !newTokens.contains(t)).sorted().toList();

        this.atmCe = atmCe;
        this.atmPe = atmPe;
        this.fixedItmCe = fixedItmCe;
        this.fixedItmPe = fixedItmPe;
        this.optionTokensByLabel = newTokensByLabel;
        this.optionContractsByLabel = newContractsByLabel;

        if (!toSubscribe.isEmpty()) {
            sendFoTokenAction(ws, 1, toSubscribe, "subscribe");
        }
        if (!toUnsubscribe.isEmpty()) {
            sendFoTokenAction(ws, 0, toUnsubscribe, "unsubscribe");
        }
        log.info("Rolling ATM/ITM re-selection applied: ATM CE strike={} ATM PE strike={} FIXED ITM CE strike={} FIXED ITM PE strike={}",
                atmCe.strike(), atmPe.strike(), fixedItmCe.strike(), fixedItmPe.strike());
    }

    private void sendFoTokenAction(WebSocket ws, int action, List<String> tokens, String actionLabel) {
        try {
            Map<String, Object> request = Map.of(
                    "correlationID", "nifty-atm-stream",
                    "action", action,
                    "params", Map.of(
                            "mode", MODE_LTP,
                            "tokenList", List.of(Map.of("exchangeType", EXCHANGE_TYPE_NSE_FO, "tokens", tokens))
                    )
            );
            String json = objectMapper.writeValueAsString(request);
            ws.sendText(json, true);
            log.info("Sent SmartStream {} request: {}", actionLabel, json);
        } catch (Exception ex) {
            log.error("Failed to send SmartStream {} request", actionLabel, ex);
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
            scheduleReconnect();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("SmartStream WebSocket closed: status={} reason={}", statusCode, reason);
            if (!stopRequested) {
                scheduleReconnect();
            }
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
            tickPersistenceService.offer(new TickRecord("NIFTY", token, price, 0.0, exchangeTimestampMillis, tickTime));
        } else if (exchangeType == EXCHANGE_TYPE_NSE_FO) {
            optionTokensByLabel.forEach((label, selectedToken) -> {
                if (token.equals(selectedToken)) {
                    OptionContract contract = optionContractsByLabel.get(label);
                    double strike = contract != null ? contract.strike() : 0.0;
                    log.info("{} = {} @ {}", label, price, tickTime);
                    tickPersistenceService.offer(new TickRecord(label, token, price, strike, exchangeTimestampMillis, tickTime));
                }
            });
        }
    }
}

