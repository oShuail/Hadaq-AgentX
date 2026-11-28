package com.example.hadaq.Entity;

import com.example.hadaq.Entity.Converter.DoubleListConverter;
import com.example.hadaq.Entity.Converter.PolygonConverter;
import com.example.hadaq.Entity.Converter.V2Converters;
import com.example.hadaq.Enum.DamageType;
import com.example.hadaq.Enum.DetectionChange;
import com.example.hadaq.Enum.Escalation;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.ReviewStatus;
import com.example.hadaq.Model.V2Values;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// one damage on one capture (v2, fnd_ + 12 hex), from the AI worker, reviewed by the conservator
@Entity
@Table(name = "findings", indexes = {
        @Index(name = "idx_finding_capture", columnList = "capture_id"),
        @Index(name = "idx_finding_inspection", columnList = "inspection_id"),
        @Index(name = "idx_finding_wall", columnList = "building_id, wall_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Finding {

    @Id
    @Column(name = "finding_id", length = 40)
    private String findingId;

    @Column(name = "capture_id", nullable = false, length = 40)
    private String captureId;

    @Column(name = "inspection_id", length = 40)
    private String inspectionId;

    @Column(name = "building_id", nullable = false, length = 10)
    private String buildingId;

    @Column(name = "wall_id", nullable = false, length = 10)
    private String wallId;

    @Column(name = "zone_id", nullable = false, length = 30)
    private String zoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "damage_type", nullable = false, length = 30)
    private DamageType damageType;

    // 1 خفيف, 2 متوسط, 3 شديد
    @Column(nullable = false)
    private Integer severity;

    @Column(nullable = false)
    private Double confidence;

    @Column(nullable = false)
    private boolean suspected;

    @Convert(converter = DoubleListConverter.class)
    @Column(name = "box_px", length = 200)
    private List<Double> boxPx;

    @Convert(converter = DoubleListConverter.class)
    @Column(name = "box_wall_m", nullable = false, length = 200)
    private List<Double> boxWallM;

    @Convert(converter = PolygonConverter.class)
    @Column(name = "polygon_wall_m", columnDefinition = "LONGTEXT")
    private List<List<Double>> polygonWallM;

    @Column(name = "length_cm")
    private Double lengthCm;

    @Column(name = "width_mm")
    private Double widthMm;

    @Column(name = "area_m2")
    private Double areaM2;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", length = 20)
    private DetectionChange change;

    @Column(name = "previous_finding_id", length = 40)
    private String previousFindingId;

    @Convert(converter = V2Converters.Lookalike.class)
    @Column(columnDefinition = "TEXT")
    private V2Values.Lookalike lookalike;

    @Convert(converter = V2Converters.Priority.class)
    @Column(columnDefinition = "TEXT")
    private V2Values.Priority priority;

    // copied from priority, so the site list can count by escalation
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Escalation escalation;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(name = "review_by", length = 60)
    private String reviewBy;

    @Column(name = "review_at", columnDefinition = "DATETIME(6)")
    private Instant reviewAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_type", length = 30)
    private DamageType correctedType;

    @Column(name = "corrected_severity")
    private Integer correctedSeverity;

    @Column(name = "review_note", length = 2000)
    private String reviewNote;

    @Column(nullable = false, length = 80)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provenance provenance = Provenance.MODEL_OUTPUT;

    @Convert(converter = V2Converters.StringList.class)
    @Column(name = "evidence_ids", columnDefinition = "TEXT")
    private List<String> evidenceIds = new ArrayList<>();

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    public V2Values.Review review() {
        return new V2Values.Review(reviewStatus, reviewBy, reviewAt, correctedType, correctedSeverity, reviewNote);
    }

    public DamageType effectiveType() {
        return correctedType != null ? correctedType : damageType;
    }

    public int effectiveSeverity() {
        return correctedSeverity != null ? correctedSeverity : severity;
    }
}
