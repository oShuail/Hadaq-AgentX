package com.example.hadaq.Entity;

import com.example.hadaq.Enum.InspectionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// one wall in one survey (v2, ins_ + 12 hex). Its captures, findings and work orders point to it
@Entity
@Table(name = "inspections", indexes = {
        @Index(name = "idx_inspection_wall", columnList = "building_id, wall_id, status"),
        @Index(name = "idx_inspection_started", columnList = "building_id, started_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Inspection {

    @Id
    @Column(name = "inspection_id", length = 40)
    private String inspectionId;

    @Column(name = "survey_id", length = 40)
    private String surveyId;

    @Column(name = "building_id", nullable = false, length = 10)
    private String buildingId;

    @Column(name = "wall_id", nullable = false, length = 10)
    private String wallId;

    @Column(name = "started_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant startedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InspectionStatus status = InspectionStatus.OPEN;

    @Column(name = "approved_by", length = 60)
    private String approvedBy;

    @Column(name = "approved_at", columnDefinition = "DATETIME(6)")
    private Instant approvedAt;
}
