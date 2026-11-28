package com.example.hadaq.Controller;

import com.example.hadaq.DTOIn.SensorReadingDTOIn;
import com.example.hadaq.Service.ReadingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ReadingController {

    private final ReadingService readingService;

    // HTTP alternative to MQTT (the sensor simulator uses it when there is no broker)
    @PostMapping("/readings")
    public ResponseEntity<?> postReading(@RequestBody @Valid SensorReadingDTOIn reading) {
        readingService.handle(reading);
        return ResponseEntity.ok(Map.of("stored", true));
    }

    // latest reading per sensor, with evidence_id
    @GetMapping("/buildings/{buildingId}/readings/latest")
    public ResponseEntity<?> latest(@PathVariable String buildingId) {
        return ResponseEntity.ok(readingService.latestDTO(buildingId));
    }

    // reading history, newest first
    @GetMapping("/buildings/{buildingId}/readings")
    public ResponseEntity<?> history(@PathVariable String buildingId,
                                     @RequestParam(name = "sensor_id", required = false) String sensorId,
                                     @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(readingService.historyDTO(buildingId, sensorId, limit));
    }
}
