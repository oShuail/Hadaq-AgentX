package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.DTOOut.EvidenceDTOOut;
import com.example.hadaq.DTOOut.SimulationDTOOut;
import com.example.hadaq.Entity.SensorReading;
import com.example.hadaq.Mapper.DTOMapper;
import com.example.hadaq.Repository.DetectionRepository;
import com.example.hadaq.Repository.EventRepository;
import com.example.hadaq.Repository.EvidenceRepository;
import com.example.hadaq.Repository.PhotoRepository;
import com.example.hadaq.Repository.SensorReadingRepository;
import com.example.hadaq.Repository.SimulationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

// GET /evidence/{id}: every ID the agent cites can be opened (rd_, evt_, ph_, sim_, det_, risk_)
@Service
@RequiredArgsConstructor
public class EvidenceService {

    private final SensorReadingRepository sensorReadingRepository;
    private final EventRepository eventRepository;
    private final PhotoRepository photoRepository;
    private final SimulationRunRepository simulationRunRepository;
    private final DetectionRepository detectionRepository;
    private final EvidenceRepository evidenceRepository;
    private final SimulationService simulationService;
    private final JsonMapper jsonMapper;

    public EvidenceDTOOut get(String evidenceId) {
        return find(evidenceId).orElseThrow(() -> ApiException.notFound("Unknown evidence id"));
    }

    private Optional<EvidenceDTOOut> find(String id) {
        if (id.startsWith(SensorReading.EVIDENCE_PREFIX)) {
            long rowId;
            try {
                rowId = Long.parseLong(id.substring(SensorReading.EVIDENCE_PREFIX.length()));
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
            return sensorReadingRepository.findById(rowId)
                    .map(r -> EvidenceDTOOut.of(id, "sensor_reading", DTOMapper.toDTO(r)));
        }
        if (id.startsWith("evt_")) {
            return eventRepository.findById(id).map(e -> EvidenceDTOOut.of(id, "event", DTOMapper.toDTO(e)));
        }
        if (id.startsWith("ph_")) {
            return photoRepository.findById(id).map(p -> EvidenceDTOOut.of(id, "photo", DTOMapper.toDTO(p)));
        }
        if (id.startsWith("sim_")) {
            return simulationRunRepository.findById(id).map(run -> {
                SimulationDTOOut sim = simulationService.toDTO(run);
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("scenario", sim.result() == null ? null : sim.result().scenario());
                value.put("engine", sim.result() == null ? null : sim.result().engine());
                value.put("summary", sim.result() == null ? null : sim.result().summary());
                value.put("threshold", sim.result() == null ? null : sim.result().threshold());
                value.put("assumptions", sim.result() == null ? null : sim.result().assumptions());
                value.put("weather_source", sim.result() == null ? null : sim.result().weatherSource());
                return new EvidenceDTOOut(id, "simulation", sim.status().value(), sim.request(), value);
            });
        }
        Optional<EvidenceDTOOut> stored = evidenceRepository.findById(id)
                .map(e -> EvidenceDTOOut.of(id, e.getKind(), jsonMapper.readTree(e.getDataJson())));
        if (stored.isPresent()) {
            return stored;
        }
        return detectionRepository.findById(id).map(d -> EvidenceDTOOut.of(id, "detection", DTOMapper.toDTO(d)));
    }
}
