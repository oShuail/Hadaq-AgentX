package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.Config.ExecutorConfig;
import com.example.hadaq.DTOIn.SimulationRequestDTOIn;
import com.example.hadaq.DTOIn.WeatherSpecDTOIn;
import com.example.hadaq.DTOOut.SimulationDTOOut;
import com.example.hadaq.DTOOut.SimulationResultDTOOut;
import com.example.hadaq.DTOOut.SimulationStartedDTOOut;
import com.example.hadaq.Entity.SimulationRun;
import com.example.hadaq.Enum.EventSource;
import com.example.hadaq.Enum.EventType;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.SimulationStatus;
import com.example.hadaq.Enum.WeatherMode;
import com.example.hadaq.Model.WallSpec;
import com.example.hadaq.Repository.SimulationRunRepository;
import com.example.hadaq.Simulation.MoistureModel;
import com.example.hadaq.Simulation.WallParams;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

// POST /simulations runs Basma's moisture model in the background, stores the result, recomputes risk, emits simulation_done
@Slf4j
@Service
public class SimulationService {

    private final SimulationRunRepository simulationRunRepository;
    private final RegistryService registryService;
    private final WeatherService weatherService;
    private final RiskService riskService;
    private final EventService eventService;
    private final LiveService liveService;
    private final JsonMapper jsonMapper;
    private final TaskExecutor worker;

    public SimulationService(SimulationRunRepository simulationRunRepository, RegistryService registryService,
                             WeatherService weatherService, RiskService riskService, EventService eventService,
                             LiveService liveService, JsonMapper jsonMapper,
                             @Qualifier(ExecutorConfig.WORKER) TaskExecutor worker) {
        this.simulationRunRepository = simulationRunRepository;
        this.registryService = registryService;
        this.weatherService = weatherService;
        this.riskService = riskService;
        this.eventService = eventService;
        this.liveService = liveService;
        this.jsonMapper = jsonMapper;
        this.worker = worker;
    }

    public SimulationStartedDTOOut start(SimulationRequestDTOIn request) {
        registryService.check(request.buildingId());
        String runId = Ids.newId("sim");
        SimulationRun run = new SimulationRun();
        run.setRunId(runId);
        run.setBuildingId(request.buildingId());
        run.setWallId(request.wallId());
        run.setScenario(request.scenario());
        run.setStatus(SimulationStatus.RUNNING);
        run.setRequestJson(jsonMapper.writeValueAsString(request));
        run.setCreatedAt(Times.now());
        simulationRunRepository.save(run);
        worker.execute(() -> runJob(runId, request));
        return new SimulationStartedDTOOut(runId, SimulationStatus.RUNNING);
    }

    void runJob(String runId, SimulationRequestDTOIn original) {
        try {
            WallSpec wall = registryService.wall(original.wallId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown wall " + original.wallId()));
            SimulationRequestDTOIn request = original;
            WeatherSpecDTOIn weather = request.weather();
            if (weather.mode() == WeatherMode.REPLAY && (weather.date() == null || weather.date().isBlank())) {
                String stormDate = registryService.demoStormDate();  // the storm Basma picked
                weather = stormDate != null
                        ? weather.withModeAndDate(WeatherMode.REPLAY, stormDate)
                        : weather.withModeAndDate(WeatherMode.SYNTHETIC_STORM, null);
                request = request.withWeather(weather);
            }
            WeatherService.WeatherResult hours = weatherService.forSpec(weather, registryService.siteLat(), registryService.siteLon());
            WallParams params = new WallParams(wall.azimuthDeg(), wall.rows(), wall.cols());
            SimulationResultDTOOut result = MoistureModel.run(request, hours.hours(), params, hours.source()).withRunId(runId);
            finish(runId, result, null);

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("run_id", runId);
            done.put("status", SimulationStatus.DONE);
            done.put("result", result);
            liveService.broadcast(LiveService.SIMULATION, done);

            Map<String, Object> risk = new LinkedHashMap<>();
            risk.put("building_id", request.buildingId());
            risk.put("scores", riskService.compute(request.buildingId()));
            liveService.broadcast(LiveService.RISK, risk);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("run_id", runId);
            payload.put("scenario", request.scenario());
            payload.put("requested_by", request.requestedBy());
            eventService.handle(EventService.create(EventType.SIMULATION_DONE, request.buildingId(),
                    EventSource.SIMULATION, Provenance.MODEL_OUTPUT, payload), true);
        } catch (Exception exception) {
            // report any failure to the caller instead of hanging
            log.error("Simulation {} failed", runId, exception);
            finish(runId, null, String.valueOf(exception.getMessage()));
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("run_id", runId);
            failed.put("status", SimulationStatus.FAILED);
            failed.put("error", String.valueOf(exception.getMessage()));
            liveService.broadcast(LiveService.SIMULATION, failed);
        }
    }

    private void finish(String runId, SimulationResultDTOOut result, String error) {
        simulationRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus(result != null ? SimulationStatus.DONE : SimulationStatus.FAILED);
            run.setResultJson(result != null ? jsonMapper.writeValueAsString(result) : null);
            run.setError(error == null ? null : error.substring(0, Math.min(error.length(), 1000)));
            simulationRunRepository.save(run);
        });
    }

    public SimulationDTOOut get(String runId) {
        return simulationRunRepository.findById(runId).map(this::toDTO)
                .orElseThrow(() -> ApiException.notFound("Unknown run"));
    }

    public SimulationDTOOut latest(String buildingId) {
        return simulationRunRepository.findFirstByBuildingIdAndStatusOrderByCreatedAtDesc(buildingId, SimulationStatus.DONE)
                .map(this::toDTO)
                .orElseThrow(() -> ApiException.notFound("No finished simulation yet"));
    }

    public SimulationDTOOut toDTO(SimulationRun run) {
        SimulationRequestDTOIn request = jsonMapper.readValue(run.getRequestJson(), SimulationRequestDTOIn.class);
        SimulationResultDTOOut result = run.getResultJson() == null ? null
                : jsonMapper.readValue(run.getResultJson(), SimulationResultDTOOut.class);
        return new SimulationDTOOut(run.getRunId(), run.getStatus(), run.getError(), request, result);
    }
}
