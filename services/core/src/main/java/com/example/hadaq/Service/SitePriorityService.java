package com.example.hadaq.Service;

import com.example.hadaq.DTOOut.V2Out;
import com.example.hadaq.Entity.Capture;
import com.example.hadaq.Entity.Finding;
import com.example.hadaq.Entity.Inspection;
import com.example.hadaq.Enum.Escalation;
import com.example.hadaq.Enum.ReviewStatus;
import com.example.hadaq.Repository.CaptureRepository;
import com.example.hadaq.Repository.FindingRepository;
import com.example.hadaq.Repository.InspectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * GET /api/v2/site/priority: one row per building, urgent first, then by days since the last survey.
 * Open findings = the findings of each wall's latest inspection that were not rejected (the wall's current state),
 * counted by escalation (the conservator's correction does not change the escalation the priority gave).
 */
@Service
@RequiredArgsConstructor
public class SitePriorityService {

    private final V2RegistryService v2RegistryService;
    private final InspectionRepository inspectionRepository;
    private final FindingRepository findingRepository;
    private final CaptureRepository captureRepository;

    public List<V2Out.SitePriorityRow> priority() {
        List<V2Out.SitePriorityRow> rows = new ArrayList<>();
        for (V2Out.Building building : v2RegistryService.registry().buildings()) {
            rows.add(row(building));
        }
        rows.sort(Comparator
                .comparingInt((V2Out.SitePriorityRow r) -> r.worstEscalation() == null ? Escalation.values().length : r.worstEscalation().ordinal())
                // never surveyed = the longest wait, so first among equals
                .thenComparing(V2Out.SitePriorityRow::lastSurveyAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        return rows;
    }

    private V2Out.SitePriorityRow row(V2Out.Building building) {
        // the latest inspection of each wall (the list is newest first)
        Set<String> walls = new LinkedHashSet<>();
        List<String> latest = new ArrayList<>();
        for (Inspection i : inspectionRepository.findByBuildingIdOrderByStartedAtDesc(building.id())) {
            if (walls.add(i.getWallId())) {
                latest.add(i.getInspectionId());
            }
        }
        List<Finding> open = latest.isEmpty() ? List.of() : findingRepository.findByInspectionIdIn(latest).stream()
                .filter(f -> f.getReviewStatus() != ReviewStatus.REJECTED && f.getEscalation() != null).toList();

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Escalation e : Escalation.values()) {
            long n = open.stream().filter(f -> f.getEscalation() == e).count();
            if (n > 0) {
                counts.put(e.value(), (int) n);
            }
        }
        Escalation worst = open.stream().map(Finding::getEscalation).min(Comparator.naturalOrder()).orElse(null);
        String topReason = open.stream().filter(f -> f.getEscalation() == worst && f.getPriority() != null)
                .max(Comparator.comparingDouble(f -> f.getPriority().score()))
                .map(f -> f.getPriority().reasonAr()).orElse(null);
        Instant lastSurvey = captureRepository.findFirstByBuildingIdOrderByCapturedAtDesc(building.id())
                .map(Capture::getCapturedAt).orElse(null);
        return new V2Out.SitePriorityRow(building.id(), building.nameAr(), building.nameEn(), worst, counts, topReason, lastSurvey);
    }
}
