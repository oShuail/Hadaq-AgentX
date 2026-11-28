package com.example.hadaq.DTOIn;

import com.example.hadaq.Enum.DamageType;
import com.example.hadaq.Enum.ReviewStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// PATCH /api/v2/findings/{id}/review (conservator only)
public record ReviewRequestDTOIn(
        @NotNull(message = "status is required (accepted, corrected or rejected)")
        ReviewStatus status,

        DamageType correctedType,

        @Min(value = 1, message = "corrected_severity must be 1, 2 or 3")
        @Max(value = 3, message = "corrected_severity must be 1, 2 or 3")
        Integer correctedSeverity,

        @Size(max = 2000, message = "note is too long")
        String note
) {
    @AssertTrue(message = "status must be accepted, corrected or rejected")
    public boolean isDecision() {
        return status != ReviewStatus.PENDING;
    }

    @AssertTrue(message = "a correction needs corrected_type or corrected_severity")
    public boolean isCorrectionComplete() {
        return status != ReviewStatus.CORRECTED || correctedType != null || correctedSeverity != null;
    }
}
