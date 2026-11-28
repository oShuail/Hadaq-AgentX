package com.example.hadaq.Controller;

import com.example.hadaq.Service.RiskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RiskController {

    private final RiskService riskService;

    // [RiskScore] per zone, highest magnitude first (empty until a simulation has finished)
    @GetMapping("/buildings/{buildingId}/risk")
    public ResponseEntity<?> risk(@PathVariable String buildingId) {
        return ResponseEntity.ok(riskService.scoresFor(buildingId));
    }
}
