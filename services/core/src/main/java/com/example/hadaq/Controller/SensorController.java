package com.example.hadaq.Controller;

import com.example.hadaq.Service.DeviceService;
import com.example.hadaq.Service.SensorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/buildings/{buildingId}")
@RequiredArgsConstructor
public class SensorController {

    private final SensorService sensorService;
    private final DeviceService deviceService;

    // every registered sensor: status, reasons, latest and the trend over the last `window` readings
    @GetMapping("/sensors")
    public ResponseEntity<?> sensors(@PathVariable String buildingId, @RequestParam(defaultValue = "60") int window) {
        return ResponseEntity.ok(sensorService.overview(buildingId, window));
    }

    // one sensor plus its history downsampled to `points`, oldest first
    @GetMapping("/sensors/{sensorId}")
    public ResponseEntity<?> sensor(@PathVariable String buildingId, @PathVariable String sensorId,
                                    @RequestParam(defaultValue = "120") int window,
                                    @RequestParam(defaultValue = "40") int points) {
        return ResponseEntity.ok(sensorService.detail(buildingId, sensorId, window, points));
    }

    // one wall zone: position, sensors, latest detections there, risk, line in the latest simulation
    @GetMapping("/zones/{zoneId}")
    public ResponseEntity<?> zone(@PathVariable String buildingId, @PathVariable String zoneId,
                                  @RequestParam(defaultValue = "60") int window) {
        return ResponseEntity.ok(sensorService.zone(buildingId, zoneId, window));
    }

    // sensor nodes: online, offline or never_seen
    @GetMapping("/devices")
    public ResponseEntity<?> devices(@PathVariable String buildingId) {
        return ResponseEntity.ok(deviceService.devices(buildingId));
    }
}
