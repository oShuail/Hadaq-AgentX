package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.SimulationStatus;

public record SimulationStartedDTOOut(String runId, SimulationStatus status) {
}
