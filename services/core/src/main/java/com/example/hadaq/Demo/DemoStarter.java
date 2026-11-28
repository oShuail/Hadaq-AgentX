package com.example.hadaq.Demo;

import com.example.hadaq.DTOIn.SimulationRequestDTOIn;
import com.example.hadaq.DTOIn.WeatherSpecDTOIn;
import com.example.hadaq.Enum.Scenario;
import com.example.hadaq.Enum.SimulationStatus;
import com.example.hadaq.Repository.SimulationRunRepository;
import com.example.hadaq.Service.RegistryService;
import com.example.hadaq.Service.SimulationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/*
 * Starts the built-in demo once the app is up, like the hosted demo's container (infra/render/start.sh):
 * the sensor feed, the camera, the mock vision worker, and a first storm simulation so the risk layer has scores
 * as soon as the page opens. Everything is synthetic. hadaq.demo.enabled=false turns all of it off.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoStarter {

    private final DemoSensorFeed sensorFeed;
    private final DemoCamera camera;
    private final MockVisionWorker visionWorker;
    private final SimulationService simulationService;
    private final SimulationRunRepository simulationRunRepository;
    private final RegistryService registryService;

    @Value("${hadaq.demo.enabled:true}")
    private boolean enabled;

    @Value("${hadaq.demo.seed-simulation:true}")
    private boolean seedSimulation;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("Built-in demo off (hadaq.demo.enabled=false): data comes only from devices and services");
            return;
        }
        sensorFeed.start();
        camera.start();
        visionWorker.start();
        String building = registryService.buildingId();
        if (seedSimulation && simulationRunRepository.findFirstByBuildingIdAndStatusOrderByCreatedAtDesc(building, SimulationStatus.DONE).isEmpty()) {
            simulationService.start(new SimulationRequestDTOIn(building, "W01", Scenario.STORM_REPLAY,
                    WeatherSpecDTOIn.defaults(), "user"));
            log.info("Demo: first storm simulation started so the risk layer has scores");
        }
    }
}
