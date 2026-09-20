package com.example.demo.service;

import com.example.demo.config.MockGreeksProperties;
import com.example.demo.config.MockSnapshotProperties;
import com.example.demo.dto.TickSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDate;
import java.util.Map;

/**
 * Pushes {@link TickPersistenceService#getLatestCompleteSnapshot()} to every connected
 * {@code /ws/tick-snapshot} WebSocket client every 5 seconds. Before sending, the raw snapshot is
 * enriched via {@link PremiumReferenceService} with the expected CE/PE premium (Delta+Gamma+Theta from
 * the 9:15 reference) and its divergence from the actual premium. Nothing is sent while no complete
 * snapshot has been produced yet, and disconnected/broken sessions are pruned as they're found.
 * Every enriched snapshot is also pushed onto {@link EnrichedSnapshotPersistenceService} for asynchronous
 * persistence into the {@code enriched_snapshot} table, regardless of whether any client is connected.
 * <p>
 * When {@link MockSnapshotProperties#isEnabled()} is {@code true} (e.g. {@code snapshot.mock.enabled=true}),
 * a dynamically generated dummy snapshot from {@link MockSnapshotService} is used instead of the real
 * latest complete snapshot, so this whole pipeline (broadcast + persistence) can be exercised outside
 * market hours when no live ticks are streaming. If {@link MockGreeksProperties#isEnabled()} is also
 * {@code true}, {@link GreeksCacheService} is fed mock Greeks (matching the mock snapshot's strikes) on
 * every cycle too, so the Gamma/Theta/Expected/Divergence fields aren't left null for lack of any cached
 * Greeks (which normally only come from the real Angel One pipeline).
 * <p>
 * Every cycle's NIFTY price is also fed into {@link NiftyCandleService} to build 5-minute OHLC candles;
 * whenever that completes a candle, {@link SupportResistanceService} recalculates the day's Support/
 * Resistance zones from all of today's completed candles.
 */
@Service
public class TickSnapshotBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(TickSnapshotBroadcastService.class);

    private final TickSnapshotWebSocketHandler webSocketHandler;
    private final PremiumReferenceService premiumReferenceService;
    private final EnrichedSnapshotPersistenceService enrichedSnapshotPersistenceService;
    private final MockSnapshotProperties mockSnapshotProperties;
    private final MockSnapshotService mockSnapshotService;
    private final MockGreeksProperties mockGreeksProperties;
    private final GreeksCacheService greeksCacheService;
    private final NiftyCandleService niftyCandleService;
    private final SupportResistanceService supportResistanceService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public TickSnapshotBroadcastService(TickSnapshotWebSocketHandler webSocketHandler,
                                         PremiumReferenceService premiumReferenceService,
                                         EnrichedSnapshotPersistenceService enrichedSnapshotPersistenceService,
                                         MockSnapshotProperties mockSnapshotProperties,
                                         MockSnapshotService mockSnapshotService,
                                         MockGreeksProperties mockGreeksProperties,
                                         GreeksCacheService greeksCacheService,
                                         NiftyCandleService niftyCandleService,
                                         SupportResistanceService supportResistanceService) {
        this.webSocketHandler = webSocketHandler;
        this.premiumReferenceService = premiumReferenceService;
        this.enrichedSnapshotPersistenceService = enrichedSnapshotPersistenceService;
        this.mockSnapshotProperties = mockSnapshotProperties;
        this.mockSnapshotService = mockSnapshotService;
        this.mockGreeksProperties = mockGreeksProperties;
        this.greeksCacheService = greeksCacheService;
        this.niftyCandleService = niftyCandleService;
        this.supportResistanceService = supportResistanceService;
    }

    @Scheduled(fixedRate = 5_000)
    void broadcastLatestSnapshot() {
        TickSnapshot snapshot = mockSnapshotProperties.isEnabled()
                ? mockSnapshotService.nextSnapshot()
                : TickPersistenceService.getLatestCompleteSnapshot();
        if (snapshot == null) {
            return;
        }

        if (mockSnapshotProperties.isEnabled()) {
            if (mockGreeksProperties.isEnabled()) {
                // Real Greeks are normally seeded by the live pipeline (TradingApplication); feed mock
                // Greeks matching the mock snapshot's strikes so they're available here too.
                greeksCacheService.refresh(LocalDate.now(), mockSnapshotService.currentContracts());
            }
            // Real snapshots have their reference captured from TickPersistenceService.offer(); mock
            // snapshots bypass that path entirely, so prime/refresh the reference here instead. Ignore
            // the 9:15 AM market-open gate for mock snapshots so testing works any time of day.
            premiumReferenceService.captureIfNeeded(snapshot, true);
        }

        if (snapshot.nifty() != null) {
            niftyCandleService.onTick(LocalDate.now(), snapshot.tickTime(), snapshot.nifty())
                    .ifPresent(completedCandle ->
                            supportResistanceService.recalculate(completedCandle.tradeDate(), snapshot.nifty()));
        }

        Map<String, Object> enriched = premiumReferenceService.enrich(snapshot);
        enrichedSnapshotPersistenceService.offer(enriched);

        if (webSocketHandler.getSessions().isEmpty()) {
            return;
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(enriched);
        } catch (Exception ex) {
            log.warn("Failed to serialize latest complete TickSnapshot for broadcast", ex);
            return;
        }

        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : webSocketHandler.getSessions().values()) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (Exception ex) {
                log.warn("Failed to send TickSnapshot to WebSocket session {}", session.getId(), ex);
            }
        }
    }
}

