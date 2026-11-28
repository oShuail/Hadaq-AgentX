package com.example.hadaq.Service;

import com.example.hadaq.DTOIn.SensorReadingDTOIn;
import com.example.hadaq.DTOOut.SensorReadingDTOOut;
import com.example.hadaq.Entity.Event;
import com.example.hadaq.Entity.SensorReading;
import com.example.hadaq.Enum.EventSource;
import com.example.hadaq.Enum.EventType;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.SensorKind;
import com.example.hadaq.Enum.SensorStatus;
import com.example.hadaq.Mapper.DTOMapper;
import com.example.hadaq.Model.HealthResult;
import com.example.hadaq.Model.SensorSpec;
import com.example.hadaq.Repository.SensorReadingRepository;
import com.example.hadaq.Simulation.MonitoringRule;
import com.example.hadaq.Util.Numbers;
import com.example.hadaq.Util.Times;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReadingService {

    private static final int HEALTH_HISTORY = 120;

    private final SensorReadingRepository sensorReadingRepository;
    private final RegistryService registryService;
    private final SensorHealthService sensorHealthService;
    private final LiveService liveService;
    private final EventService eventService;
    private final AnomalyCooldownService anomalyCooldownService;

    /*
     * every reading (MQTT or POST /readings): store it (evidence ID rd_<id>), push it live with the sensor's health,
     * and run Basma's moisture rule. A faulty, implausible or stuck sensor must not raise a moisture alarm.
     */
    public void handle(SensorReadingDTOIn dto) {
        SensorReading saved = sensorReadingRepository.save(DTOMapper.toEntity(dto));
        List<SensorReading> history = history(saved.getBuildingId(), saved.getSensorId(), HEALTH_HISTORY);
        SensorSpec spec = specFor(saved);
        List<List<SensorReading>> peers = SensorHealthService.isFlat(history) ? peers(saved.getBuildingId(), spec, null) : null;
        HealthResult health = sensorHealthService.health(spec, history, Times.now(), peers);
        liveService.broadcast(LiveService.READING, DTOMapper.toLiveDTO(saved, health));

        // late uploads (stale) may still raise it
        if (saved.getKind() != SensorKind.MOISTURE
                || (health.status() != SensorStatus.OK && health.status() != SensorStatus.STALE)) {
            return;
        }
        List<MonitoringRule.TimedValue> usable = history.stream().filter(SensorHealthService::usable)
                .map(r -> new MonitoringRule.TimedValue(r.getTs(), r.getValue())).toList();
        Map<String, Object> anomaly = MonitoringRule.detectMoistureAnomaly(usable);
        if (anomaly != null && anomalyCooldownService.tryFire(saved.getSensorId())) {
            Provenance provenance = saved.getProvenance() == Provenance.MEASURED ? Provenance.DERIVED : Provenance.SYNTHETIC;
            Map<String, Object> payload = new LinkedHashMap<>(anomaly);
            payload.put("sensor_id", saved.getSensorId());
            payload.put("zone_id", saved.getZoneId());
            payload.put("reading_evidence_id", saved.getEvidenceId());
            Event event = EventService.create(EventType.MOISTURE_ANOMALY, saved.getBuildingId(), EventSource.MONITORING, provenance, payload);
            eventService.handle(event, true);
        }
    }

    // newest first
    public List<SensorReading> history(String buildingId, String sensorId, int limit) {
        Limit max = Limit.of(Math.max(1, limit));
        return sensorId == null || sensorId.isBlank()
                ? sensorReadingRepository.findByBuildingIdOrderByTsDescIdDesc(buildingId, max)
                : sensorReadingRepository.findByBuildingIdAndSensorIdOrderByTsDescIdDesc(buildingId, sensorId, max);
    }

    public List<SensorReadingDTOOut> historyDTO(String buildingId, String sensorId, int limit) {
        registryService.check(buildingId);
        return history(buildingId, sensorId, Numbers.clamp(limit, 1, 2000)).stream().map(DTOMapper::toDTO).toList();
    }

    // the latest reading of every registered sensor that has one
    public List<SensorReading> latest(List<String> sensorIds) {
        return sensorIds.stream()
                .map(sensorReadingRepository::findFirstBySensorIdOrderByTsDescIdDesc)
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    public List<SensorReadingDTOOut> latestDTO(String buildingId) {
        registryService.check(buildingId);
        return latest(registryService.sensors().stream().map(SensorSpec::id).toList())
                .stream().map(DTOMapper::toDTO).toList();
    }

    /*
     * recent histories of the other sensors of the same kind (to tell a stuck sensor from a calm wall).
     * histories: already loaded histories by sensor id, or null to load them
     */
    public List<List<SensorReading>> peers(String buildingId, SensorSpec spec, Map<String, List<SensorReading>> histories) {
        return registryService.sensorsOfKind(spec.kind()).stream()
                .filter(s -> !s.id().equals(spec.id()))
                .map(s -> histories != null ? histories.get(s.id())
                        : history(buildingId, s.id(), SensorHealthService.FLATLINE_N * 2))
                .filter(h -> h != null && !h.isEmpty())
                .toList();
    }

    private SensorSpec specFor(SensorReading reading) {
        return registryService.sensor(reading.getSensorId()).orElse(new SensorSpec(reading.getSensorId(),
                reading.getKind(), reading.getUnit(), reading.getZoneId(), reading.getDeviceId(), null, null));
    }
}
