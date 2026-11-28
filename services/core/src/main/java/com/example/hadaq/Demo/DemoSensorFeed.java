package com.example.hadaq.Demo;

import com.example.hadaq.Config.ExecutorConfig;
import com.example.hadaq.DTOIn.SensorReadingDTOIn;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Events.HadaqEventStored;
import com.example.hadaq.Model.SensorSpec;
import com.example.hadaq.Service.DeviceService;
import com.example.hadaq.Service.ReadingService;
import com.example.hadaq.Service.RegistryService;
import com.example.hadaq.Util.Times;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/*
 * Built-in sensor simulator (port of devices/sensor-sim/sensor_sim.py, owner: Saleh), so the API alone shows live
 * readings like the hosted demo. Readings go through the same path as POST /readings and are labelled "synthetic".
 * Turn it off (hadaq.demo.sensors=false) when the Python simulator or the real ESP32 node is sending,
 * and hadaq.demo.include-real=false to leave S01 to S03 to the brick node.
 */
@Slf4j
@Service
public class DemoSensorFeed {

    private final ReadingService readingService;
    private final RegistryService registryService;
    private final TaskScheduler scheduler;
    private final boolean enabled;
    private final boolean includeReal;
    private final double intervalSeconds;
    private final double speed;

    private WallModel world;
    private List<SensorSpec> published;
    private ScheduledFuture<?> task;

    public DemoSensorFeed(ReadingService readingService, RegistryService registryService,
                          @Qualifier(ExecutorConfig.SCHEDULER) TaskScheduler scheduler,
                          @Value("${hadaq.demo.enabled:true}") boolean demoEnabled,
                          @Value("${hadaq.demo.sensors:true}") boolean sensors,
                          @Value("${hadaq.demo.include-real:true}") boolean includeReal,
                          @Value("${hadaq.demo.sensor-interval-seconds:2}") double intervalSeconds,
                          @Value("${hadaq.demo.speed:720}") double speed) {
        this.readingService = readingService;
        this.registryService = registryService;
        this.scheduler = scheduler;
        this.enabled = demoEnabled && sensors;
        this.includeReal = includeReal;
        this.intervalSeconds = intervalSeconds;
        this.speed = speed;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void start() {
        if (!enabled || task != null) {
            return;
        }
        // the model needs every sensor (the base moisture drives the tilt); only the selected ones are published
        List<SensorSpec> all = registryService.sensors();
        world = new WallModel(all, Instant.now(), null);
        published = all.stream().filter(s -> "sim".equals(s.source()) || includeReal).toList();
        task = scheduler.scheduleAtFixedRate(this::tick, Duration.ofMillis((long) (intervalSeconds * 1000)));
        log.info("Demo sensor feed: {} every {} s at {}x (synthetic)", published.stream().map(SensorSpec::id).toList(), intervalSeconds, speed);
    }

    void tick() {
        try {
            double nowReal = System.nanoTime() / 1e9;
            world.advance(intervalSeconds * speed / 3600);
            String ts = Times.now().toString();
            for (SensorSpec sensor : published) {
                WallModel.Reading reading = world.read(sensor, nowReal);
                if (reading.value() == null) {
                    continue;  // dropout: the sensor sends nothing
                }
                String device = "sim".equals(sensor.source()) ? DeviceService.SIM_DEVICE : sensor.deviceId();
                readingService.handle(new SensorReadingDTOIn(sensor.id(), registryService.buildingId(), sensor.zoneId(),
                        sensor.kind(), reading.value(), sensor.unit(), ts, Provenance.SYNTHETIC, device, reading.quality()));
            }
        } catch (Exception exception) {
            // one bad round must not stop the feed
            log.warn("Demo sensor round failed: {}", exception.getMessage());
        }
    }

    // storm_started, storm_ended, sensor_fault, new_crack, demo_reset (from the web, the agents, MQTT or the demo run)
    @EventListener
    public void onEvent(HadaqEventStored stored) {
        WallModel model = world;
        if (model != null) {
            model.onEvent(stored.event().type().value(), stored.event().payload(), System.nanoTime() / 1e9);
        }
    }

    @PreDestroy
    synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }
}
