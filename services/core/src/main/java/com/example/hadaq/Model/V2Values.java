package com.example.hadaq.Model;

import com.example.hadaq.Enum.CitationStatus;
import com.example.hadaq.Enum.DamageType;
import com.example.hadaq.Enum.Escalation;
import com.example.hadaq.Enum.Exposure;
import com.example.hadaq.Enum.ReviewStatus;
import com.example.hadaq.Enum.TaskStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/*
 * The small value objects of contracts v2 (packages/hadaq_schemas/hadaq_schemas/inspection.py), used inside requests,
 * responses and the JSON columns of the v2 entities. JSON is snake_case on the wire like everything else.
 */
public final class V2Values {

    private V2Values() {
    }

    public record LonLat(double lon, double lat) {
    }

    // start = LEFT end when you face the facade (the C1 side)
    public record BaseLine(LonLat start, LonLat end) {
    }

    public record GeoFix(@NotNull(message = "gps.lat is required") Double lat,
                         @NotNull(message = "gps.lon is required") Double lon,
                         Double accuracyM) {
    }

    // checks the app made at the shutter. Warnings never block a shot; they are recorded
    public record CaptureQuality(Boolean tiltOk, Exposure exposure, Double blurScore, Boolean steady, Boolean scaleRef,
                                 List<String> warnings) {
        public CaptureQuality {
            if (scaleRef == null) {
                scaleRef = false;
            }
            if (warnings == null) {
                warnings = new ArrayList<>();
            }
        }

        public static CaptureQuality empty() {
            return new CaptureQuality(null, null, null, null, false, new ArrayList<>());
        }
    }

    // the photo cannot tell two damages apart: the agent asks for evidence instead of guessing
    public record Lookalike(@NotBlank(message = "lookalike.pair_id is required") String pairId,
                            List<String> options, List<String> evidenceNeeded, String noteAr) {
    }

    public record Review(ReviewStatus status, String by, Instant at, DamageType correctedType,
                         @Min(value = 1, message = "corrected_severity must be 1, 2 or 3")
                         @Max(value = 3, message = "corrected_severity must be 1, 2 or 3") Integer correctedSeverity,
                         String note) {
        public Review {
            if (status == null) {
                status = ReviewStatus.PENDING;
            }
        }

        public static Review pending() {
            return new Review(ReviewStatus.PENDING, null, null, null, null, null);
        }
    }

    // DGDA's criteria in their order, each 0..1 (0 when not sent)
    public record PriorityCriteria(
            @DecimalMin(value = "0", message = "priority criteria are 0 to 1") @DecimalMax(value = "1", message = "priority criteria are 0 to 1") Double safety,
            @DecimalMin(value = "0", message = "priority criteria are 0 to 1") @DecimalMax(value = "1", message = "priority criteria are 0 to 1") Double active,
            @DecimalMin(value = "0", message = "priority criteria are 0 to 1") @DecimalMax(value = "1", message = "priority criteria are 0 to 1") Double waterBeforeRain,
            // snake_case would make "severity_xarea"; the contract says severity_x_area
            @JsonProperty("severity_x_area")
            @DecimalMin(value = "0", message = "priority criteria are 0 to 1") @DecimalMax(value = "1", message = "priority criteria are 0 to 1") Double severityXArea,
            @DecimalMin(value = "0", message = "priority criteria are 0 to 1") @DecimalMax(value = "1", message = "priority criteria are 0 to 1") Double value,
            @DecimalMin(value = "0", message = "priority criteria are 0 to 1") @DecimalMax(value = "1", message = "priority criteria are 0 to 1") Double location) {
        public PriorityCriteria {
            safety = safety == null ? 0.0 : safety;
            active = active == null ? 0.0 : active;
            waterBeforeRain = waterBeforeRain == null ? 0.0 : waterBeforeRain;
            severityXArea = severityXArea == null ? 0.0 : severityXArea;
            value = value == null ? 0.0 : value;
            location = location == null ? 0.0 : location;
        }
    }

    public record Priority(
            @NotNull(message = "priority.score is required")
            @DecimalMin(value = "0", message = "priority.score must be 0 to 100")
            @DecimalMax(value = "100", message = "priority.score must be 0 to 100") Double score,
            @NotNull(message = "priority.criteria is required") @Valid PriorityCriteria criteria,
            @NotNull(message = "priority.escalation is required") Escalation escalation,
            @NotBlank(message = "priority.reason_ar is required") String reasonAr,
            String reasonEn,
            String method) {
        public Priority {
            if (method == null) {
                method = "DGDA criteria, weights ASSUMED (hadaq_agents.priority)";
            }
        }
    }

    // section is never invented: left empty when unknown
    public record Citation(@NotBlank(message = "citation.source is required") String source, String section,
                           @NotNull(message = "citation.status is required") CitationStatus status) {
    }

    public record WorkOrderLocation(@NotBlank(message = "location.building_id is required") String buildingId,
                                    @NotBlank(message = "location.wall_id is required") String wallId,
                                    @NotEmpty(message = "location.zone_ids is required") List<String> zoneIds,
                                    @Size(min = 4, max = 4, message = "location.box_wall_m must be [u0, v0, u1, v1]") List<Double> boxWallM) {
    }

    // one wall to photograph: required_shots "full" or column ranges like "C1-C2"
    public record SurveyTask(String buildingId, String wallId, List<String> requiredShots, List<String> doneShots,
                             TaskStatus status) {
        public SurveyTask {
            requiredShots = requiredShots == null || requiredShots.isEmpty() ? new ArrayList<>(List.of("full")) : new ArrayList<>(requiredShots);
            doneShots = doneShots == null ? new ArrayList<>() : new ArrayList<>(doneShots);
            if (status == null) {
                status = TaskStatus.NOT_STARTED;
            }
        }
    }
}
