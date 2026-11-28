package com.example.hadaq.Controller;

import com.example.hadaq.DTOIn.SensorReadingDTOIn;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.ReadingQuality;
import com.example.hadaq.Enum.SensorKind;
import com.example.hadaq.Service.LiveService;
import com.example.hadaq.Service.ReadingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// a browser on ws://.../ws/live gets {"channel": "reading", "data": {...}} with the sensor status
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveSocketTest {

    @LocalServerPort
    private int port;
    @Autowired
    private ReadingService readingService;
    @Autowired
    private LiveService liveService;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void readingIsPushedLive() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                received.add(message.getPayload());
            }
        }, "ws://localhost:" + port + "/ws/live").get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 50 && liveService.clientCount() == 0; i++) {
            Thread.sleep(20);
        }

        readingService.handle(new SensorReadingDTOIn("B01-S06", "B01", "B01-W01-R2C5", SensorKind.CRACK_WIDTH, 1.2,
                "mm", Instant.now().toString(), Provenance.SYNTHETIC, "sim-01", ReadingQuality.OK));

        String raw = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(raw, "no live message");
        JsonNode message = jsonMapper.readTree(raw);
        assertEquals("reading", message.path("channel").asString());
        assertEquals("B01-S06", message.path("data").path("sensor_id").asString());
        assertEquals("crack_width", message.path("data").path("kind").asString());
        assertEquals("ok", message.path("data").path("status").asString());
        assertTrue(message.path("data").path("evidence_id").asString().startsWith("rd_"));
        session.close();
    }
}
