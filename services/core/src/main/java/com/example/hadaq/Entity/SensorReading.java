package com.example.hadaq.Entity;

import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.ReadingQuality;
import com.example.hadaq.Enum.SensorKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// one sensor reading. Its evidence ID is "rd_" + id
@Entity
@Table(name = "sensor_readings", indexes = {
        @Index(name = "idx_reading_sensor_ts", columnList = "sensor_id, ts"),
        @Index(name = "idx_reading_building_ts", columnList = "building_id, ts")
})
@Getter
@Setter
@NoArgsConstructor
public class SensorReading {

    public static final String EVIDENCE_PREFIX = "rd_";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sensor_id", nullable = false, length = 20)
    private String sensorId;

    @Column(name = "building_id", nullable = false, length = 10)
    private String buildingId;

    @Column(name = "zone_id", nullable = false, length = 30)
    private String zoneId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SensorKind kind;

    @Column(name = "reading_value", nullable = false)
    private Double value;

    @Column(nullable = false, length = 10)
    private String unit;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant ts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provenance provenance;

    @Column(name = "device_id", length = 40)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReadingQuality quality = ReadingQuality.OK;

    public String getEvidenceId() {
        return id == null ? null : EVIDENCE_PREFIX + id;
    }
}
