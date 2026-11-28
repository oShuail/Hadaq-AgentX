package com.example.hadaq.Controller;

import com.example.hadaq.Service.EvidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EvidenceController {

    private final EvidenceService evidenceService;

    // the record behind any evidence ID (rd_, evt_, ph_, det_, sim_, risk_)
    @GetMapping("/evidence/{evidenceId}")
    public ResponseEntity<?> get(@PathVariable String evidenceId) {
        return ResponseEntity.ok(evidenceService.get(evidenceId));
    }
}
