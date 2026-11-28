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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

// POST /simulations (v1 and /api/v2) runs Basma's moisture model in the background: on her simulation service when
// SIM_URL is set (T5), else, or when it fails, on the Java port. Stores the result, recomputes risk, emits simulation_done
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
    private final SimServiceClient simServiceClient;
    private final V2RegistryService v2RegistryService;
    private final InspectionService inspectionService;

    public SimulationService(SimulationRunRepository simulationRunRepository, RegistryService registryService,
                             WeatherService weatherService, RiskService riskService, EventService eventService,
                             LiveService liveService, JsonMapper jsonMapper,
                             @Qualifier(ExecutorConfig.WORKER) TaskExecutor worker, SimServiceClient simServiceClient,
                             V2RegistryService v2RegistryService, InspectionService inspectionService) {
        this.simServiceClient = simServiceClient;
        this.v2RegistryService = v2RegistryService;
        this.inspectionService = inspectionService;
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
            SimulationRequestDTOIn request = original;
            WeatherSpecDTOIn weather = request.weather();
            if (weather.mode() == WeatherMode.REPLAY && (weather.date() == null || weather.date().isBlank())) {
                String stormDate = registryService.demoStormDate();  // the storm Basma picked
                weather = stormDate != null
                        ? weather.withModeAndDate(WeatherMode.REPLAY, stormDate)
                        : weather.withModeAndDate(WeatherMode.SYNTHETIC_STORM, null);
                request = request.withWeather(weather);
            }
            // T5: Basma's simulation service when SIM_URL is set, the Java port when it is not or when it fails
            JsonNode result;
            String engineSource;
            if (simServiceClient.enabled()) {
                try {
                    result = runOnSimService(runId, request);
                    engineSource = "sim_service";
                } catch (Exception down) {
                    log.warn("Simulation service at {} failed ({}); running the Java model", simServiceClient.url(), down.getMessage());
                    result = runJavaModel(runId, request, "Simulation service at " + simServiceClient.url()
                            + " was not available (" + down.getMessage() + "); the core API's Java port of bucket_v0 ran instead.");
                    engineSource = "java_fallback";
                }
            } else {
                result = runJavaModel(runId, request, null);
                engineSource = "java";
            }
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
            payload.put("engine_source", engineSource);  // sim_service, java_fallback or java
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

    /*
     * POST {SIM_URL}/sim/run (contracts/README.md "v2", simulation service). The answer must read as a SimulationResult;
     * it is kept as it is (Basma's extras included, e.g. the first hour each zone crosses critical), with our run_id.
     */
    private JsonNode runOnSimService(String runId, SimulationRequestDTOIn request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", runId);
        body.put("building_id", request.buildingId());
        body.put("wall", v2RegistryService.wall(request.buildingId(), request.wallId()));
        body.put("scenario", request.scenario());
        body.put("weather", request.weather());
        body.put("approved_findings", inspectionService.previousFindings(request.buildingId(), request.wallId()));
        body.put("requested_by", request.requestedBy());
        JsonNode answer = simServiceClient.run(body);
        if (answer == null || !answer.isObject()) {
            throw new IllegalStateException("the answer is not a JSON object");
        }
        SimulationResultDTOOut checked = jsonMapper.treeToValue(answer, SimulationResultDTOOut.class);
        if (checked.summary() == null || checked.summary().isEmpty()) {
            throw new IllegalStateException("the answer has no summary per zone");
        }
        ObjectNode result = (ObjectNode) answer.deepCopy();
        result.put("run_id", runId);
        return result;
    }

    // the Java port of Basma's bucket_v0; note = why it ran instead of the service (added to the assumptions)
    private JsonNode runJavaModel(String runId, SimulationRequestDTOIn request, String note) {
        WallSpec wall = registryService.wall(request.wallId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown wall " + request.wallId()));
        WeatherService.WeatherResult hours = weatherService.forSpec(request.weather(), registryService.siteLat(), registryService.siteLon());
        WallParams params = new WallParams(wall.azimuthDeg(), wall.rows(), wall.cols());
        SimulationResultDTOOut result = MoistureModel.run(request, hours.hours(), params, hours.source()).withRunId(runId);
        ObjectNode node = jsonMapper.valueToTree(result);
        if (note != null) {
            ((ArrayNode) node.get("assumptions")).add(note);
        }
        return node;
    }

    private void finish(String runId, JsonNode result, String error) {
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
        // as stored: the Java model's result, or the simulation service's with its extra fields
        JsonNode result = run.getResultJson() == null ? null : jsonMapper.readTree(run.getResultJson());
        return new SimulationDTOOut(run.getRunId(), run.getStatus(), run.getError(), request, result);
    }
}
