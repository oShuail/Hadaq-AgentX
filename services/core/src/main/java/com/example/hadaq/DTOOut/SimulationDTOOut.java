package com.example.hadaq.DTOOut;

import com.example.hadaq.DTOIn.SimulationRequestDTOIn;
import com.example.hadaq.Enum.SimulationStatus;

// GET /simulations/{run_id} and /buildings/{b}/simulations/latest
public record SimulationDTOOut(
        String runId,
        SimulationStatus status,
        String error,
        SimulationRequestDTOIn request,
        SimulationResultDTOOut result
) {
}
