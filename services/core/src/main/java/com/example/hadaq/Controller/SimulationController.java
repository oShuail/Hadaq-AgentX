package com.example.hadaq.Controller;

import com.example.hadaq.DTOIn.SimulationRequestDTOIn;
import com.example.hadaq.Service.SimulationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;

    // starts a run in the background: {"run_id", "status": "running"}. Poll GET /simulations/{run_id}
    @PostMapping("/simulations")
    public ResponseEntity<?> start(@RequestBody @Valid SimulationRequestDTOIn request) {
        return ResponseEntity.ok(simulationService.start(request));
    }

    @GetMapping("/simulations/{runId}")
    public ResponseEntity<?> get(@PathVariable String runId) {
        return ResponseEntity.ok(simulationService.get(runId));
    }

    // 404 when no run has finished yet
    @GetMapping("/buildings/{buildingId}/simulations/latest")
    public ResponseEntity<?> latest(@PathVariable String buildingId) {
        return ResponseEntity.ok(simulationService.latest(buildingId));
    }
}
