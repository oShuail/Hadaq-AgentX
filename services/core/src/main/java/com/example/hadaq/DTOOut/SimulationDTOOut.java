package com.example.hadaq.DTOOut;

import com.example.hadaq.DTOIn.SimulationRequestDTOIn;
import com.example.hadaq.Enum.SimulationStatus;
import tools.jackson.databind.JsonNode;

// GET /simulations/{run_id} and /buildings/{b}/simulations/latest. result = the SimulationResult as stored: the Java
// model's, or the simulation service's with any extra fields it adds (e.g. the first hour each zone crosses critical)
public record SimulationDTOOut(
        String runId,
        SimulationStatus status,
        String error,
        SimulationRequestDTOIn request,
        JsonNode result
) {
}
