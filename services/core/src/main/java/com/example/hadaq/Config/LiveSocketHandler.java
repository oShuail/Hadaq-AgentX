package com.example.hadaq.Config;

import com.example.hadaq.Service.LiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

// /ws/live: we only push. Clients may send pings, they are ignored
@Component
@RequiredArgsConstructor
public class LiveSocketHandler extends TextWebSocketHandler {

    private final LiveService liveService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        liveService.connect(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // ping from the browser, nothing to do
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        liveService.disconnect(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        liveService.disconnect(session);
    }
}
