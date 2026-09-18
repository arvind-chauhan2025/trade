package com.example.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plain WebSocket handler for {@code /ws/tick-snapshot}. Clients connect and simply receive the
 * latest complete {@link com.example.demo.dto.TickSnapshot} pushed every 5 seconds by
 * {@link TickSnapshotBroadcastService}; no messages are expected from the client.
 */
@Component
public class TickSnapshotWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TickSnapshotWebSocketHandler.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("WebSocket client connected: {} (total {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket client disconnected: {} (total {})", session.getId(), sessions.size());
    }

    Map<String, WebSocketSession> getSessions() {
        return sessions;
    }
}
