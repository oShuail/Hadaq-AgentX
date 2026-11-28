package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.Scenario;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

// the contract SimulationResult: moisture per zone per hour (zones), and a summary per zone
public record SimulationResultDTOOut(
        String runId,
        String buildingId,
        String wallId,
        Scenario scenario,
        String engine,
        Provenance provenance,
        Instant createdAt,
        List<OffsetDateTime> times,
        Map<String, List<Double>> zones,
        Map<String, ZoneSummaryDTOOut> summary,
        double threshold,
        List<String> assumptions,
        String weatherSource
) {
    public SimulationResultDTOOut withRunId(String newRunId) {
        return new SimulationResultDTOOut(newRunId, buildingId, wallId, scenario, engine, provenance, createdAt,
                times, zones, summary, threshold, assumptions, weatherSource);
    }
}
