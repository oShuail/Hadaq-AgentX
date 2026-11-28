package com.example.hadaq.Controller;

import com.example.hadaq.Service.RegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/buildings")
@RequiredArgsConstructor
public class BuildingController {

    private final RegistryService registryService;

    // the registry for that building (walls, zones, sensors, cameras), as in contracts/building_b01.json
    @GetMapping("/{buildingId}")
    public ResponseEntity<?> getBuilding(@PathVariable String buildingId) {
        return ResponseEntity.ok(registryService.registryFor(buildingId));
    }
}
