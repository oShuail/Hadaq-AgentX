package com.example.hadaq.Controller;

import com.example.hadaq.DTOOut.HealthDTOOut;
import com.example.hadaq.Service.MqttService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final MqttService mqttService;

    @GetMapping("/health")
    public ResponseEntity<HealthDTOOut> health() {
        return ResponseEntity.ok(new HealthDTOOut("ok", mqttService.isConnected()));
    }
}
