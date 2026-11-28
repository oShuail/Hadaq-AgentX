package com.example.hadaq.Controller;

import com.example.hadaq.DTOIn.EventDTOIn;
import com.example.hadaq.Service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    // stored, pushed live, published to MQTT; storm_started / moisture_anomaly / detection_ready go to the agents
    @PostMapping("/events")
    public ResponseEntity<?> postEvent(@RequestBody @Valid EventDTOIn event) {
        return ResponseEntity.ok(eventService.post(event));
    }

    // recent events, newest first
    @GetMapping("/buildings/{buildingId}/events")
    public ResponseEntity<?> recent(@PathVariable String buildingId, @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(eventService.recent(buildingId, limit));
    }
}
