package com.example.hadaq.Controller;

import com.example.hadaq.DTOIn.SimulationRequestDTOIn;
import com.example.hadaq.Service.EvidenceService;
import com.example.hadaq.Service.SimulationService;
import com.example.hadaq.Service.SitePriorityService;
import com.example.hadaq.Service.SurveyService;
import com.example.hadaq.Service.V2RegistryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// v2: registry, surveys, the site's priority list, simulations and evidence
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class SiteController {

    private final V2RegistryService v2RegistryService;
    private final SurveyService surveyService;
    private final SitePriorityService sitePriorityService;
    private final SimulationService simulationService;
    private final EvidenceService evidenceService;

    @GetMapping("/registry")
    public ResponseEntity<?> registry() {
        return ResponseEntity.ok(v2RegistryService.registry());
    }

    // the surveyor app's task list
    @GetMapping("/surveys")
    public ResponseEntity<?> surveys(@RequestParam(required = false) String assignee) {
        return ResponseEntity.ok(surveyService.list(assignee));
    }

    // one row per building, urgent first, then by days since the last survey
    @GetMapping("/site/priority")
    public ResponseEntity<?> priority() {
        return ResponseEntity.ok(sitePriorityService.priority());
    }

    // v1 SimulationRequest. Runs the built-in port of Basma's engine until the simulation service (:8200) has /sim/run
    @PostMapping("/simulations")
    public ResponseEntity<?> simulate(@RequestBody @Valid SimulationRequestDTOIn request) {
        return ResponseEntity.ok(simulationService.start(request));
    }

    // the record behind any v2 id (srv_, cap_, fnd_, wo_, ins_) or v1 id
    @GetMapping("/evidence/{evidenceId}")
    public ResponseEntity<?> evidence(@PathVariable String evidenceId) {
        return ResponseEntity.ok(evidenceService.get(evidenceId));
    }
}
