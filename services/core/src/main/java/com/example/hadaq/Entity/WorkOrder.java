package com.example.hadaq.Entity;

import com.example.hadaq.Entity.Converter.V2Converters;
import com.example.hadaq.Enum.Escalation;
import com.example.hadaq.Enum.WorkOrderStatus;
import com.example.hadaq.Model.V2Values;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// a treatment draft for one or more findings (v2, wo_ + 12 hex). Needs a field check before anyone works on the wall
@Entity
@Table(name = "work_orders", indexes = {
        @Index(name = "idx_work_order_inspection", columnList = "inspection_id"),
        @Index(name = "idx_work_order_capture", columnList = "capture_id")
})
@Getter
@Setter
@NoArgsConstructor
public class WorkOrder {

    @Id
    @Column(name = "work_order_id", length = 40)
    private String workOrderId;

    @Column(name = "inspection_id", length = 40)
    private String inspectionId;

    // the capture whose results brought it (not in the contract JSON; used to replace a capture's results)
    @Column(name = "capture_id", length = 40)
    private String captureId;

    @Convert(converter = V2Converters.StringList.class)
    @Column(name = "finding_ids", nullable = false, columnDefinition = "TEXT")
    private List<String> findingIds = new ArrayList<>();

    @Convert(converter = V2Converters.Location.class)
    @Column(columnDefinition = "TEXT")
    private V2Values.WorkOrderLocation location;

    @Column(name = "treatment_id", nullable = false, length = 60)
    private String treatmentId;

    @Convert(converter = V2Converters.StringList.class)
    @Column(name = "steps_ar", columnDefinition = "TEXT")
    private List<String> stepsAr = new ArrayList<>();

    @Convert(converter = V2Converters.StringList.class)
    @Column(name = "steps_en", columnDefinition = "TEXT")
    private List<String> stepsEn = new ArrayList<>();

    @Convert(converter = V2Converters.StringList.class)
    @Column(name = "materials_ar", columnDefinition = "TEXT")
    private List<String> materialsAr = new ArrayList<>();

    @Convert(converter = V2Converters.StringList.class)
    @Column(name = "materials_en", columnDefinition = "TEXT")
    private List<String> materialsEn = new ArrayList<>();

    @Convert(converter = V2Converters.StringList.class)
    @Column(name = "tools_ar", columnDefinition = "TEXT")
    private List<String> toolsAr = new ArrayList<>();

    @Convert(converter = V2Converters.StringList.class)
    @Column(name = "tools_en", columnDefinition = "TEXT")
    private List<String> toolsEn = new ArrayList<>();

    @Column(name = "fix_water_source_first", nullable = false)
    private boolean fixWaterSourceFirst;

    @Convert(converter = V2Converters.Citation.class)
    @Column(columnDefinition = "TEXT")
    private V2Values.Citation citation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Escalation escalation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkOrderStatus status = WorkOrderStatus.DRAFT_NEEDS_FIELD_CHECK;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;
}
