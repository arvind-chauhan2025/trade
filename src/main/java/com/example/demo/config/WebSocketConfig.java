package com.example.demo.config;

import com.example.demo.service.TickSnapshotWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TickSnapshotWebSocketHandler tickSnapshotWebSocketHandler;

    public WebSocketConfig(TickSnapshotWebSocketHandler tickSnapshotWebSocketHandler) {
        this.tickSnapshotWebSocketHandler = tickSnapshotWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(tickSnapshotWebSocketHandler, "/ws/tick-snapshot")
                .setAllowedOrigins("*");
    }
}
