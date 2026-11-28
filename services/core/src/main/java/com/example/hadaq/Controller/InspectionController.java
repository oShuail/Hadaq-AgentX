package com.example.hadaq.Controller;

import com.example.hadaq.DTOIn.ReviewRequestDTOIn;
import com.example.hadaq.Service.InspectionService;
import com.example.hadaq.Util.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// v2 inspections and the conservator's review
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspectionService;

    @GetMapping("/inspections/{inspectionId}")
    public ResponseEntity<?> get(@PathVariable String inspectionId) {
        return ResponseEntity.ok(inspectionService.get(inspectionId));
    }

    // newest first
    @GetMapping("/buildings/{buildingId}/inspections")
    public ResponseEntity<?> forBuilding(@PathVariable String buildingId) {
        return ResponseEntity.ok(inspectionService.forBuilding(buildingId));
    }

    // conservator only (X-Hadaq-User: conservator)
    @PatchMapping("/findings/{findingId}/review")
    public ResponseEntity<?> review(@PathVariable String findingId, @RequestBody @Valid ReviewRequestDTOIn request,
                                    @RequestHeader(value = Roles.HEADER, required = false) String user) {
        return ResponseEntity.ok(inspectionService.review(findingId, request, user));
    }

    // conservator only; 409 while any finding is pending
    @PostMapping("/inspections/{inspectionId}/approve")
    public ResponseEntity<?> approve(@PathVariable String inspectionId,
                                     @RequestHeader(value = Roles.HEADER, required = false) String user) {
        return ResponseEntity.ok(inspectionService.approve(inspectionId, user));
    }
}
