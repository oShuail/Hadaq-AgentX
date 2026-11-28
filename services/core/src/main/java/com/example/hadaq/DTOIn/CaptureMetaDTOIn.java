package com.example.hadaq.DTOIn;

import com.example.hadaq.Enum.CaptureSource;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Model.V2Values;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

// the `meta` JSON part of POST /api/v2/captures, written by the surveyor app (contracts v2 CaptureMeta)
public record CaptureMetaDTOIn(
        // made on the phone; the same value on every retry (dedupe)
        @NotBlank(message = "client_uuid is required")
        @Size(min = 8, max = 80, message = "client_uuid must be 8 to 80 characters")
        String clientUuid,

        String surveyId,

        @NotBlank(message = "building_id is required")
        String buildingId,

        @NotBlank(message = "wall_id is required")
        String wallId,

        // "full" or a column range like "C1-C2"
        @Size(max = 20, message = "segment is too long")
        String segment,

        @NotBlank(message = "captured_at is required")
        String capturedAt,

        @NotBlank(message = "surveyor is required")
        @Size(max = 60, message = "surveyor is too long")
        String surveyor,

        @Size(max = 120, message = "device is too long")
        String device,

        CaptureSource source,

        @Valid
        V2Values.GeoFix gps,

        Double headingDeg,
        Double tiltDeg,

        @Valid
        V2Values.CaptureQuality quality,

        // the wall's 4 corners in image pixels: top-left, top-right, bottom-right, bottom-left (left = the C1 end)
        @Size(min = 4, max = 4, message = "corners_px must be 4 points [x, y]: top-left, top-right, bottom-right, bottom-left")
        List<@Size(min = 2, max = 2, message = "each corner in corners_px must be [x, y]") List<Double>> cornersPx,

        @Min(value = 1, message = "image_width_px must be positive")
        Integer imageWidthPx,

        @Min(value = 1, message = "image_height_px must be positive")
        Integer imageHeightPx,

        // faces the phone hid; null = the phone did not check
        @Min(value = 0, message = "faces_hidden cannot be negative")
        Integer facesHidden,

        Provenance provenance
) {
    public CaptureMetaDTOIn {
        if (segment == null || segment.isBlank()) {
            segment = "full";
        }
        if (source == null) {
            source = CaptureSource.CAMERA;
        }
        if (quality == null) {
            quality = V2Values.CaptureQuality.empty();
        }
        if (provenance == null) {
            provenance = Provenance.MEASURED;
        }
    }
}
