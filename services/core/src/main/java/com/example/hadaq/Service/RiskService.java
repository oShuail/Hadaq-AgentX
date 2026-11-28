package com.example.hadaq.Service;

import com.example.hadaq.DTOOut.RiskScoreDTOOut;
import com.example.hadaq.DTOOut.SimulationResultDTOOut;
import com.example.hadaq.Entity.Detection;
import com.example.hadaq.Entity.Evidence;
import com.example.hadaq.Entity.SimulationRun;
import com.example.hadaq.Enum.SimulationStatus;
import com.example.hadaq.Repository.DetectionRepository;
import com.example.hadaq.Repository.EvidenceRepository;
import com.example.hadaq.Repository.SimulationRunRepository;
import com.example.hadaq.Simulation.RiskScorer;
import com.example.hadaq.Util.Times;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// ABC risk per zone from the latest finished simulation + the latest detections. Cached until something changes
@Service
@RequiredArgsConstructor
public class RiskService {

    private final SimulationRunRepository simulationRunRepository;
    private final DetectionRepository detectionRepository;
    private final EvidenceRepository evidenceRepository;
    private final RegistryService registryService;
    private final JsonMapper jsonMapper;

    private final Map<String, List<RiskScoreDTOOut>> cache = new ConcurrentHashMap<>();

    public List<RiskScoreDTOOut> scores(String buildingId) {
        List<RiskScoreDTOOut> cached = cache.get(buildingId);
        return cached == null || cached.isEmpty() ? compute(buildingId) : cached;
    }

    public List<RiskScoreDTOOut> scoresFor(String buildingId) {
        registryService.check(buildingId);
        return scores(buildingId);
    }

    public void invalidate(String buildingId) {
        cache.remove(buildingId);
    }

    // every score is also stored as evidence (risk_ + 12 hex), so the agent can cite it
    public List<RiskScoreDTOOut> compute(String buildingId) {
        Optional<SimulationResultDTOOut> sim = latestResult(buildingId);
        if (sim.isEmpty()) {
            return List.of();
        }
        List<Detection> detections = detectionRepository.findLatestSet(buildingId);
        List<RiskScoreDTOOut> scores = RiskScorer.scoreBuilding(sim.get(), detections,
                registryService.heavyRainDaysPerYear(), registryService.valueWeightOverrides(),
                registryService.valueWeightDefault());
        for (RiskScoreDTOOut score : scores) {
            Evidence evidence = new Evidence();
            evidence.setEvidenceId(score.riskId());
            evidence.setBuildingId(buildingId);
            evidence.setKind("risk");
            evidence.setDataJson(jsonMapper.writeValueAsString(score));
            evidence.setCreatedAt(Times.now());
            evidenceRepository.save(evidence);
        }
        cache.put(buildingId, scores);
        return scores;
    }

    public Optional<SimulationResultDTOOut> latestResult(String buildingId) {
        return simulationRunRepository.findFirstByBuildingIdAndStatusOrderByCreatedAtDesc(buildingId, SimulationStatus.DONE)
                .map(SimulationRun::getResultJson)
                .filter(json -> json != null && !json.isBlank())
                .map(json -> jsonMapper.readValue(json, SimulationResultDTOOut.class));
    }
}
