package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.DTOOut.DetectionDTOOut;
import com.example.hadaq.DTOOut.RiskScoreDTOOut;
import com.example.hadaq.DTOOut.SensorDetailDTOOut;
import com.example.hadaq.DTOOut.SensorOverviewDTOOut;
import com.example.hadaq.DTOOut.SimulationResultDTOOut;
import com.example.hadaq.DTOOut.ZoneDetailDTOOut;
import com.example.hadaq.DTOOut.ZoneSummaryDTOOut;
import com.example.hadaq.Entity.SensorReading;
import com.example.hadaq.Model.SensorSpec;
import com.example.hadaq.Model.WallSpec;
import com.example.hadaq.Model.ZoneRef;
import com.example.hadaq.Util.Numbers;
import com.example.hadaq.Util.ZoneIds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// what the agents read before they trust a number: status, reasons, latest reading and trend per sensor
@Service
@RequiredArgsConstructor
public class SensorService {

    private final RegistryService registryService;
    private final ReadingService readingService;
    private final SensorHealthService sensorHealthService;
    private final DetectionService detectionService;
    private final RiskService riskService;

    // every registered sensor, trend over the last `window` readings
    public List<SensorOverviewDTOOut> overview(String buildingId, int window) {
        registryService.check(buildingId);
        int size = Numbers.clamp(window, 2, 2000);
        Map<String, List<SensorReading>> histories = new LinkedHashMap<>();
        for (SensorSpec spec : registryService.sensors()) {
            histories.put(spec.id(), readingService.history(buildingId, spec.id(), Math.max(size, SensorHealthService.FLATLINE_N * 2)));
        }
        return registryService.sensors().stream().map(spec -> {
            List<SensorReading> history = histories.get(spec.id());
            return sensorHealthService.overview(spec, history.subList(0, Math.min(size, history.size())),
                    readingService.peers(buildingId, spec, histories));
        }).toList();
    }

    // one sensor: the overview plus its history over the window, downsampled to `points` (oldest first)
    public SensorDetailDTOOut detail(String buildingId, String sensorId, int window, int points) {
        registryService.check(buildingId);
        SensorSpec spec = registryService.sensor(sensorId)
                .orElseThrow(() -> ApiException.notFound("Unknown sensor " + sensorId));
        List<SensorReading> history = readingService.history(buildingId, sensorId, Numbers.clamp(window, 2, 2000));
        SensorOverviewDTOOut overview = sensorHealthService.overview(spec, history, readingService.peers(buildingId, spec, null));
        return SensorDetailDTOOut.of(overview, SensorHealthService.downsample(history, Numbers.clamp(points, 2, 500)));
    }

    // everything known about one wall zone: its sensors, the latest detections there, its risk, its simulation line
    public ZoneDetailDTOOut zone(String buildingId, String zoneId, int window) {
        registryService.check(buildingId);
        ZoneRef ref = ZoneIds.parse(zoneId);
        WallSpec wall = registryService.wall(ref.wallId()).orElse(null);
        if (!ref.buildingId().equals(buildingId) || wall == null
                || ref.row() < 1 || ref.row() > wall.rows() || ref.col() < 1 || ref.col() > wall.cols()) {
            throw ApiException.notFound("Unknown zone " + zoneId);
        }
        int size = Numbers.clamp(window, 2, 2000);
        List<SensorOverviewDTOOut> sensors = registryService.sensors().stream()
                .filter(s -> zoneId.equals(s.zoneId()))
                .map(s -> sensorHealthService.overview(s, readingService.history(buildingId, s.id(), size),
                        readingService.peers(buildingId, s, null)))
                .toList();
        List<DetectionDTOOut> detections = detectionService.latest(buildingId).stream()
                .filter(d -> zoneId.equals(d.zoneId())).toList();
        RiskScoreDTOOut risk = riskService.scores(buildingId).stream()
                .filter(r -> zoneId.equals(r.zoneId())).findFirst().orElse(null);

        Map<String, Object> simulation = null;
        Optional<SimulationResultDTOOut> sim = riskService.latestResult(buildingId);
        if (sim.isPresent() && sim.get().summary() != null && sim.get().summary().containsKey(zoneId)) {
            ZoneSummaryDTOOut summary = sim.get().summary().get(zoneId);
            simulation = new LinkedHashMap<>();
            simulation.put("run_id", sim.get().runId());
            simulation.put("peak_moisture", summary.peakMoisture());
            simulation.put("hours_above_threshold", summary.hoursAboveThreshold());
            simulation.put("erosion_index", summary.erosionIndex());
        }
        String position = ref.row() == 1 ? "base" : ref.row() == wall.rows() ? "top" : "middle";
        return new ZoneDetailDTOOut(zoneId, ref.wallId(), ref.row(), ref.col(), position, sensors, detections, risk, simulation);
    }
}
