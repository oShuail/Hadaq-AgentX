package com.example.hadaq.Controller;

import com.example.hadaq.DTOIn.DetectionBatchDTOIn;
import com.example.hadaq.Service.DetectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class DetectionController {

    private final DetectionService detectionService;

    // the vision worker posts one batch per photo (an empty list = looked, found nothing)
    @PostMapping("/detections")
    public ResponseEntity<?> post(@RequestBody @Valid DetectionBatchDTOIn batch) {
        return ResponseEntity.ok(Map.of("stored", detectionService.saveBatch(batch)));
    }

    // all detections of the most recently analysed photo
    @GetMapping("/buildings/{buildingId}/detections/latest")
    public ResponseEntity<?> latest(@PathVariable String buildingId) {
        return ResponseEntity.ok(detectionService.latest(buildingId));
    }
}
