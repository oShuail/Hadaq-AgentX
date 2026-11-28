package com.example.hadaq.Controller;

import com.example.hadaq.Service.SensorRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Saleh's two-minute sensor run (Activity, Play sensor run)
@RestController
@RequestMapping("/buildings/{buildingId}/demo/sensor-run")
@RequiredArgsConstructor
public class DemoController {

    private final SensorRunService sensorRunService;

    @GetMapping
    public ResponseEntity<?> status(@PathVariable String buildingId) {
        return ResponseEntity.ok(sensorRunService.status(buildingId));
    }

    // start or replay it (503 when the sensor simulator is not running)
    @PostMapping
    public ResponseEntity<?> start(@PathVariable String buildingId) {
        return ResponseEntity.ok(sensorRunService.start(buildingId));
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop(@PathVariable String buildingId) {
        return ResponseEntity.ok(sensorRunService.stop(buildingId));
    }
}
