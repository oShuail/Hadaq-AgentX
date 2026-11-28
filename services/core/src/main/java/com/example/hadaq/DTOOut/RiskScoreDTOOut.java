package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.RiskLevel;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// adapted ABC method per zone. A, B, C stay upper case in the JSON (contract), magnitude = A + B + C
public record RiskScoreDTOOut(
        String riskId,
        String buildingId,
        String zoneId,
        String hazard,
        @JsonProperty("A") double a,
        @JsonProperty("B") double b,
        @JsonProperty("C") double c,
        double magnitude,
        RiskLevel level,
        List<String> evidenceIds,
        String method,
        Provenance provenance
) {
}
