package com.example.hadaq.DTOIn;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

// POST /api/v2/surveys (site_manager only), contracts v2 SurveyRequest. done_shots and status are set by the core API
public record SurveyRequestDTOIn(
        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title is too long (200 characters max)")
        String title,

        // user id of the surveyor who gets it in the app
        @Size(max = 60, message = "assignee is too long")
        String assignee,

        String due,

        @NotEmpty(message = "tasks needs at least one wall")
        List<@Valid Task> tasks
) {
    public SurveyRequestDTOIn {
        if (assignee == null || assignee.isBlank()) {
            assignee = "surveyor";
        }
    }

    public record Task(
            @NotBlank(message = "tasks.building_id is required")
            String buildingId,

            @NotBlank(message = "tasks.wall_id is required")
            String wallId,

            // "full" or a column range like "C1-C2"
            @Size(min = 1, message = "tasks.required_shots needs at least one shot")
            List<@Pattern(regexp = "^(full|C\\d+-C\\d+)$", message = "required_shots must be 'full' or a column range like 'C1-C2'") String> requiredShots
    ) {
        public Task {
            if (requiredShots == null) {
                requiredShots = new ArrayList<>(List.of("full"));
            }
        }
    }
}
