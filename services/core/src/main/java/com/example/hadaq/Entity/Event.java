package com.example.hadaq.Entity;

import com.example.hadaq.Entity.Converter.JsonMapConverter;
import com.example.hadaq.Enum.EventSource;
import com.example.hadaq.Enum.EventType;
import com.example.hadaq.Enum.Provenance;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// event_id comes from the sender (evt_ + 12 hex), so the MQTT echo of our own event is stored only once
@Entity
@Table(name = "events", indexes = {
        @Index(name = "idx_event_building_ts", columnList = "building_id, ts"),
        @Index(name = "idx_event_type", columnList = "type")
})
@Getter
@Setter
@NoArgsConstructor
public class Event {

    @Id
    @Column(name = "event_id", length = 40)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventType type;

    @Column(name = "building_id", nullable = false, length = 10)
    private String buildingId;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant ts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventSource source;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provenance provenance;
}
