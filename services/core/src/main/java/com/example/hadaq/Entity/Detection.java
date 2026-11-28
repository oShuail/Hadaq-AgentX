package com.example.hadaq.Entity;

import com.example.hadaq.Entity.Converter.DoubleListConverter;
import com.example.hadaq.Entity.Converter.PolygonConverter;
import com.example.hadaq.Enum.DefectClass;
import com.example.hadaq.Enum.DetectionChange;
import com.example.hadaq.Enum.Provenance;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

// one damage found by the vision worker on one photo
@Entity
@Table(name = "detections", indexes = {
        @Index(name = "idx_detection_photo", columnList = "photo_id"),
        @Index(name = "idx_detection_building_created", columnList = "building_id, created_at"),
        @Index(name = "idx_detection_zone", columnList = "zone_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Detection {

    @Id
    @Column(name = "detection_id", length = 40)
    private String detectionId;

    @Column(name = "photo_id", nullable = false, length = 40)
    private String photoId;

    @Column(name = "camera_id", nullable = false, length = 20)
    private String cameraId;

    @Column(name = "building_id", nullable = false, length = 10)
    private String buildingId;

    @Column(name = "zone_id", nullable = false, length = 30)
    private String zoneId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DefectClass cls;

    @Column(nullable = false)
    private Double confidence;

    @Convert(converter = DoubleListConverter.class)
    @Column(name = "bbox_xyxy", nullable = false, length = 200)
    private List<Double> bboxXyxy;

    @Convert(converter = PolygonConverter.class)
    @Column(columnDefinition = "LONGTEXT")
    private List<List<Double>> polygon;

    @Column(name = "length_px")
    private Double lengthPx;

    @Column(name = "area_px")
    private Double areaPx;

    @Column(name = "length_mm")
    private Double lengthMm;

    @Column(name = "area_cm2")
    private Double areaCm2;

    @Column(nullable = false, length = 60)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provenance provenance = Provenance.MODEL_OUTPUT;

    // "change" is a reserved word in MySQL
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", length = 20)
    private DetectionChange change;

    @Column(name = "growth_pct")
    private Double growthPct;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;
}
