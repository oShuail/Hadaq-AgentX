package com.example.hadaq.DTOIn;

import com.example.hadaq.Enum.DamageType;
import com.example.hadaq.Enum.DetectionChange;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Model.V2Values;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// one damage on one capture, as the AI worker sends it inside CaptureResults (contracts v2 Finding)
public record FindingDTOIn(
        @Size(max = 40, message = "finding_id is too long")
        String findingId,

        @NotBlank(message = "finding.capture_id is required")
        String captureId,

        String inspectionId,
        String buildingId,
        String wallId,

        @NotBlank(message = "finding.zone_id is required")
        @Size(max = 30, message = "finding.zone_id is too long")
        String zoneId,

        @NotNull(message = "finding.damage_type is required")
        DamageType damageType,

        // 1 خفيف, 2 متوسط, 3 شديد
        @NotNull(message = "finding.severity is required")
        @Min(value = 1, message = "finding.severity must be 1, 2 or 3")
        @Max(value = 3, message = "finding.severity must be 1, 2 or 3")
        Integer severity,

        @NotNull(message = "finding.confidence is required")
        @DecimalMin(value = "0", message = "finding.confidence must be between 0 and 1")
        @DecimalMax(value = "1", message = "finding.confidence must be between 0 and 1")
        Double confidence,

        Boolean suspected,

        @Size(min = 4, max = 4, message = "finding.box_px must be 4 numbers")
        List<Double> boxPx,

        // [u0, v0, u1, v1] in wall metres
        @NotNull(message = "finding.box_wall_m is required")
        @Size(min = 4, max = 4, message = "finding.box_wall_m must be [u0, v0, u1, v1]")
        List<Double> boxWallM,

        List<List<Double>> polygonWallM,
        Double lengthCm,
        Double widthMm,
        Double areaM2,
        DetectionChange change,
        String previousFindingId,

        @Valid
        V2Values.Lookalike lookalike,

        @Valid
        V2Values.Priority priority,

        // sent by the worker as "pending"; the core API always starts a finding as pending
        V2Values.Review review,

        @NotBlank(message = "finding.model is required")
        String model,

        Provenance provenance,
        List<String> evidenceIds
) {
    // out of single-photo scope: only ever reported as suspected (contracts v2)
    public static final Set<DamageType> SUSPECTED_ONLY = Set.of(DamageType.STRUCTURAL_DEFORMATION, DamageType.INCOMPATIBLE_REPAIR);

    public FindingDTOIn {
        if (suspected == null) {
            suspected = false;
        }
        if (provenance == null) {
            provenance = Provenance.MODEL_OUTPUT;
        }
        if (evidenceIds == null) {
            evidenceIds = new ArrayList<>();
        }
    }

    @AssertTrue(message = "structural_deformation and incompatible_repair can only be reported as suspected")
    public boolean isSuspectedOnlyRespected() {
        return damageType == null || !SUSPECTED_ONLY.contains(damageType) || Boolean.TRUE.equals(suspected);
    }
}
