package com.example.hadaq.Service;

import com.example.hadaq.DTOIn.EventDTOIn;
import com.example.hadaq.DTOIn.SensorReadingDTOIn;
import com.example.hadaq.DTOOut.EventDTOOut;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/*
 * MQTT bridge: subscribes to hadaq/v1/#, stores everything, pushes it live, runs the monitoring rule.
 * hadaq / v1 / {building} / sensors | events | devices / ...
 * The API is the only service that writes to the database. Without a broker the API still works over HTTP.
 */
@Slf4j
@Service
public class MqttService {

    public static final String TOPIC_ROOT = "hadaq/v1";
    public static final String TOPIC_ALL = TOPIC_ROOT + "/#";

    private final JsonMapper jsonMapper;
    private final Validator validator;
    // lazy: these services publish through this one
    private final ObjectProvider<ReadingService> readingService;
    private final ObjectProvider<EventService> eventService;
    private final ObjectProvider<DeviceService> deviceService;

    private final boolean enabled;
    private final String host;
    private final int port;

    private MqttAsyncClient client;
    private volatile boolean connected;
    private volatile boolean stopping;
    private Thread connector;

    public MqttService(JsonMapper jsonMapper, Validator validator, ObjectProvider<ReadingService> readingService,
                       ObjectProvider<EventService> eventService, ObjectProvider<DeviceService> deviceService,
                       @Value("${mqtt.enabled:true}") boolean enabled,
                       @Value("${mqtt.host:localhost}") String host,
                       @Value("${mqtt.port:1883}") int port) {
        this.jsonMapper = jsonMapper;
        this.validator = validator;
        this.readingService = readingService;
        this.eventService = eventService;
        this.deviceService = deviceService;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
    }

    public boolean isConnected() {
        return connected;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("MQTT disabled (mqtt.enabled=false). The API works over HTTP only.");
            return;
        }
        try {
            client = new MqttAsyncClient("tcp://" + host + ":" + port, "hadaq-api-" + System.currentTimeMillis() / 1000, new MemoryPersistence());
        } catch (MqttException exception) {
            log.warn("MQTT not started ({}). The API still works over HTTP.", exception.getMessage());
            return;
        }
        client.setCallback(new Callback());
        // keep trying in the background until the broker is up (docker compose up mqtt), like paho connect_async in Python
        connector = new Thread(this::connectLoop, "hadaq-mqtt-connect");
        connector.setDaemon(true);
        connector.start();
    }

    private void connectLoop() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setKeepAliveInterval(30);
        options.setConnectionTimeout(5);
        options.setMaxInflight(1000);
        boolean warned = false;
        while (!stopping && !client.isConnected()) {
            try {
                client.connect(options).waitForCompletion(10_000);
                return;  // connectComplete() subscribes
            } catch (MqttException exception) {
                if (!warned) {
                    log.warn("MQTT broker {}:{} not reachable ({}). Retrying every 5 s; the API still works over HTTP.",
                            host, port, exception.getMessage());
                    warned = true;
                }
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // our own events go out again so the simulators (they listen on MQTT) react to them
    public void publishEvent(EventDTOOut event) {
        if (!connected || client == null) {
            return;
        }
        try {
            String topic = TOPIC_ROOT + "/" + event.buildingId() + "/events/" + event.type().value();
            MqttMessage message = new MqttMessage(jsonMapper.writeValueAsBytes(event));
            message.setQos(1);
            client.publish(topic, message);
        } catch (Exception exception) {
            log.warn("Could not publish event {} to MQTT: {}", event.eventId(), exception.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        stopping = true;
        if (connector != null) {
            connector.interrupt();
        }
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnect().waitForCompletion(2_000);
                }
                client.close();
            } catch (MqttException exception) {
                log.debug("MQTT close: {}", exception.getMessage());
            }
        }
    }

    private class Callback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverUri) {
            connected = true;
            try {
                client.subscribe(TOPIC_ALL, 1);
                log.info("MQTT connected to {}, subscribed to {}", serverUri, TOPIC_ALL);
            } catch (MqttException exception) {
                log.warn("MQTT subscribe failed: {}", exception.getMessage());
            }
        }

        @Override
        public void connectionLost(Throwable cause) {
            connected = false;
            log.warn("MQTT connection lost ({}), reconnecting", cause == null ? "unknown" : cause.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            String[] parts = topic.split("/");
            if (parts.length < 5) {
                return;
            }
            String kind = parts[3];
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            try {
                switch (kind) {
                    case "sensors" -> {
                        SensorReadingDTOIn reading = jsonMapper.readValue(payload, SensorReadingDTOIn.class);
                        if (valid(topic, reading)) {
                            readingService.getObject().handle(reading);
                        }
                    }
                    case "events" -> {
                        EventDTOIn event = jsonMapper.readValue(payload, EventDTOIn.class);
                        if (valid(topic, event)) {
                            eventService.getObject().receive(event);
                        }
                    }
                    case "devices" -> deviceService.getObject().handleStatus(parts[4], payload.trim());
                    default -> {
                        // not ours
                    }
                }
            } catch (Exception exception) {
                // a bad message must never stop the bridge
                log.warn("Bad payload on {}: {}", topic, exception.getMessage());
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // qos 1 acknowledged
        }

        private <T> boolean valid(String topic, T dto) {
            Set<ConstraintViolation<T>> violations = validator.validate(dto);
            if (!violations.isEmpty()) {
                log.warn("Bad payload on {}: {}", topic, violations.iterator().next().getMessage());
                return false;
            }
            return true;
        }
    }
}
