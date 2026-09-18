package com.example.demo.service;

import com.example.demo.dto.TickSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * Pushes {@link TickPersistenceService#getLatestCompleteSnapshot()} to every connected
 * {@code /ws/tick-snapshot} WebSocket client every 5 seconds. Before sending, the raw snapshot is
 * enriched via {@link PremiumReferenceService} with the expected CE/PE premium (Delta+Gamma+Theta from
 * the 9:15 reference) and its divergence from the actual premium. Nothing is sent while no complete
 * snapshot has been produced yet, and disconnected/broken sessions are pruned as they're found.
 */
@Service
public class TickSnapshotBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(TickSnapshotBroadcastService.class);

    private final TickSnapshotWebSocketHandler webSocketHandler;
    private final PremiumReferenceService premiumReferenceService;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public TickSnapshotBroadcastService(TickSnapshotWebSocketHandler webSocketHandler,
                                         PremiumReferenceService premiumReferenceService) {
        this.webSocketHandler = webSocketHandler;
        this.premiumReferenceService = premiumReferenceService;
    }

    @Scheduled(fixedRate = 5_000)
    void broadcastLatestSnapshot() {
        TickSnapshot snapshot = TickPersistenceService.getLatestCompleteSnapshot();
        if (snapshot == null || webSocketHandler.getSessions().isEmpty()) {
            return;
        }

        Map<String, Object> enriched = premiumReferenceService.enrich(snapshot);

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

