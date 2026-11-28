package com.example.hadaq.Entity;

import com.example.hadaq.Entity.Converter.PolygonConverter;
import com.example.hadaq.Entity.Converter.V2Converters;
import com.example.hadaq.Enum.CaptureSource;
import com.example.hadaq.Enum.CaptureStatus;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.Scale;
import com.example.hadaq.Model.V2Values;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

// one photo uploaded by the surveyor app (v2, cap_ + 12 hex). client_uuid is made on the phone: retries are deduplicated
// (its unique index is made by Config/SchemaFixes: Hibernate's syntax for it fails on MariaDB 10.4)
@Entity
@Table(name = "captures", indexes = {
        @Index(name = "idx_capture_inspection", columnList = "inspection_id"),
        @Index(name = "idx_capture_building_captured", columnList = "building_id, captured_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Capture {

    @Id
    @Column(name = "capture_id", length = 40)
    private String captureId;

    @Column(name = "client_uuid", nullable = false, length = 80)
    private String clientUuid;

    @Column(name = "survey_id", length = 40)
    private String surveyId;

    @Column(name = "inspection_id", length = 40)
    private String inspectionId;

    @Column(name = "building_id", nullable = false, length = 10)
    private String buildingId;

    @Column(name = "wall_id", nullable = false, length = 10)
    private String wallId;

    @Column(nullable = false, length = 20)
    private String segment = "full";

    @Column(name = "captured_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant capturedAt;

    @Column(nullable = false, length = 60)
    private String surveyor;

    @Column(length = 120)
    private String device;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CaptureSource source = CaptureSource.CAMERA;

    @Convert(converter = V2Converters.GeoFix.class)
    @Column(length = 200)
    private V2Values.GeoFix gps;

    @Column(name = "heading_deg")
    private Double headingDeg;

    @Column(name = "tilt_deg")
    private Double tiltDeg;

    @Convert(converter = V2Converters.CaptureQuality.class)
    @Column(columnDefinition = "TEXT")
    private V2Values.CaptureQuality quality;

    @Convert(converter = PolygonConverter.class)
    @Column(name = "corners_px", length = 300)
    private List<List<Double>> cornersPx;

    @Column(name = "image_width_px")
    private Integer imageWidthPx;

    @Column(name = "image_height_px")
    private Integer imageHeightPx;

    // faces the phone hid; null = the phone did not check
    @Column(name = "faces_hidden")
    private Integer facesHidden;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provenance provenance = Provenance.MEASURED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaptureStatus status = CaptureStatus.UPLOADED;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column(name = "rectified_path", length = 500)
    private String rectifiedPath;

    @Column(name = "rectified_url", length = 500)
    private String rectifiedUrl;

    @Column(name = "px_per_m")
    private Double pxPerM;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Scale scale;

    // faces hidden on the server side (core API when the phone did not check, plus the AI worker)
    @Column(name = "faces_hidden_server")
    private Integer facesHiddenServer;

    // the core API's own part of faces_hidden_server (not in the contract JSON; keeps a re-analysis from counting twice)
    @Column(name = "faces_hidden_core")
    private Integer facesHiddenCore;

    @Column(name = "findings_count", nullable = false)
    private int findingsCount;

    @Column(nullable = false)
    private boolean urgent;

    @Column(length = 1000)
    private String error;

    @Column(name = "summary_ar", columnDefinition = "TEXT")
    private String summaryAr;

    @Column(name = "summary_en", columnDefinition = "TEXT")
    private String summaryEn;

    @Column(length = 80)
    private String detector;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;
}
