package com.example.hadaq.Service;

import com.example.hadaq.Api.LiveMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// websocket fan-out: any code (HTTP thread, MQTT thread, simulation thread) can push a LiveMessage to every browser
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveService {

    public static final String READING = "reading";
    public static final String EVENT = "event";
    public static final String PHOTO = "photo";
    public static final String DETECTION = "detection";
    public static final String SIMULATION = "simulation";
    public static final String RISK = "risk";
    // v2 (contracts/README.md "v2"; the v1 channels stay as they are)
    public static final String CAPTURE = "capture";
    public static final String FINDING = "finding";
    public static final String REVIEW = "review";
    public static final String WORK_ORDER = "work_order";
    public static final String INSPECTION = "inspection";

    private final JsonMapper jsonMapper;

    // the decorator makes sending thread safe (a raw WebSocketSession is not)
    private final Map<String, WebSocketSession> clients = new ConcurrentHashMap<>();

    public void connect(WebSocketSession session) {
        clients.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024));
    }

    public void disconnect(WebSocketSession session) {
        clients.remove(session.getId());
    }

    public int clientCount() {
        return clients.size();
    }

    public void broadcast(String channel, Object data) {
        if (clients.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(jsonMapper.writeValueAsString(new LiveMessage(channel, data)));
        for (Map.Entry<String, WebSocketSession> entry : clients.entrySet()) {
            try {
                entry.getValue().sendMessage(message);
            } catch (Exception exception) {
                // a closed socket must not break the others
                log.debug("Dropping websocket client {}: {}", entry.getKey(), exception.getMessage());
                clients.remove(entry.getKey());
            }
        }
    }
}
