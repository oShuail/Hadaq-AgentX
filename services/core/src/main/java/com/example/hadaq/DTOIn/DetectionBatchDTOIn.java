package com.example.hadaq.DTOIn;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

// what the vision worker posts for one photo (an empty list means: looked, found nothing)
public record DetectionBatchDTOIn(
        @NotBlank(message = "photo_id is required")
        String photoId,

        @NotBlank(message = "building_id is required")
        String buildingId,

        @NotBlank(message = "camera_id is required")
        String cameraId,

        @NotBlank(message = "model is required")
        String model,

        List<@Valid DetectionDTOIn> detections
) {
    public DetectionBatchDTOIn {
        if (detections == null) {
            detections = new ArrayList<>();
        }
    }
}
