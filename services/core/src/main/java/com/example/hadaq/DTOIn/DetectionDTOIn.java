package com.example.hadaq.DTOIn;

import com.example.hadaq.Enum.DefectClass;
import com.example.hadaq.Enum.DetectionChange;
import com.example.hadaq.Enum.Provenance;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// one damage found on a photo (vision worker). detection_id optional: the API fills det_ + 12 hex
public record DetectionDTOIn(
        @Size(max = 40, message = "detection_id is too long")
        String detectionId,

        @NotBlank(message = "photo_id is required")
        String photoId,

        @NotBlank(message = "camera_id is required")
        String cameraId,

        @NotBlank(message = "building_id is required")
        String buildingId,

        @NotBlank(message = "zone_id is required")
        @Size(max = 30, message = "zone_id is too long")
        String zoneId,

        @NotNull(message = "cls is required")
        DefectClass cls,

        @NotNull(message = "confidence is required")
        @DecimalMin(value = "0.0", message = "confidence must be between 0 and 1")
        @DecimalMax(value = "1.0", message = "confidence must be between 0 and 1")
        Double confidence,

        @NotNull(message = "bbox_xyxy is required")
        @Size(min = 4, max = 4, message = "bbox_xyxy must have 4 numbers [x0, y0, x1, y1]")
        List<Double> bboxXyxy,

        List<List<Double>> polygon,
        Double lengthPx,
        Double areaPx,
        Double lengthMm,
        Double areaCm2,

        @NotBlank(message = "model is required")
        @Size(max = 60, message = "model is too long")
        String model,

        Provenance provenance,
        DetectionChange change,
        Double growthPct
) {
    public DetectionDTOIn {
        if (provenance == null) {
            provenance = Provenance.MODEL_OUTPUT;
        }
    }
}
