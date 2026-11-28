package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.Config.ExecutorConfig;
import com.example.hadaq.DTOOut.SensorRunStatusDTOOut;
import com.example.hadaq.Enum.EventSource;
import com.example.hadaq.Enum.EventType;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Util.Numbers;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/*
 * Saleh's two-minute B01 run: the API times the scenario events, the sensor simulator sends the readings.
 * 0 s demo_reset + storm_started (25 mm/h), 35 s storm_ended, 60 s sensor_fault (S04 spike), 75 s new_crack. All synthetic.
 */
@Service
public class SensorRunService {

    private static final String BUILDING = "B01";
    private static final double DURATION_S = 120;
    private static final Object[][] SCHEDULE = {
            {35, EventType.STORM_ENDED},
            {60, EventType.SENSOR_FAULT},
            {75, EventType.NEW_CRACK}};

    private final TaskScheduler scheduler;
    private final EventService eventService;
    private final DeviceService deviceService;
    private final RegistryService registryService;

    private final List<ScheduledFuture<?>> pending = new ArrayList<>();
    private ScheduledFuture<?> end;
    private Instant startedAt;

    public SensorRunService(@Qualifier(ExecutorConfig.SCHEDULER) TaskScheduler scheduler, EventService eventService,
                            DeviceService deviceService, RegistryService registryService) {
        this.scheduler = scheduler;
        this.eventService = eventService;
        this.deviceService = deviceService;
        this.registryService = registryService;
    }

    public SensorRunStatusDTOOut status(String buildingId) {
        registryService.check(buildingId);
        return status(deviceService.simOnline(buildingId));
    }

    public SensorRunStatusDTOOut start(String buildingId) {
        registryService.check(buildingId);
        if (!deviceService.simOnline(buildingId)) {
            throw ApiException.unavailable("Start the sensor simulator first (make sensors)");
        }
        synchronized (this) {
            cancel();
            emit(EventType.DEMO_RESET);
            emit(EventType.STORM_STARTED);
            startedAt = Instant.now();
            for (Object[] step : SCHEDULE) {
                EventType type = (EventType) step[1];
                pending.add(scheduler.schedule(() -> emit(type), startedAt.plusSeconds((Integer) step[0])));
            }
            end = scheduler.schedule(() -> { }, startedAt.plusSeconds((long) DURATION_S));
        }
        return status(true);
    }

    public SensorRunStatusDTOOut stop(String buildingId) {
        registryService.check(buildingId);
        synchronized (this) {
            cancel();
            emit(EventType.DEMO_RESET);
        }
        return status(deviceService.simOnline(buildingId));
    }

    private synchronized SensorRunStatusDTOOut status(boolean available) {
        double elapsed = startedAt == null ? 0
                : Math.min(DURATION_S, Math.max(0, Duration.between(startedAt, Instant.now()).toMillis() / 1000.0));
        boolean running = end != null && !end.isDone();
        return new SensorRunStatusDTOOut(available, running, Numbers.round(elapsed, 1), DURATION_S);
    }

    private void cancel() {
        pending.forEach(f -> f.cancel(false));
        pending.clear();
        if (end != null) {
            end.cancel(false);
        }
        end = null;
        startedAt = null;
    }

    // a heavier storm than the scenario panel's 12 mm/h so the base crosses 60 %rel inside the two minutes
    private void emit(EventType type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (type == EventType.STORM_STARTED) {
            payload.put("peak_rain_mm_h", 25);
            payload.put("wind_ms", 8);
            payload.put("wind_dir_deg", 270);
        }
        eventService.handle(EventService.create(type, BUILDING, EventSource.SCENARIO_PANEL, Provenance.SYNTHETIC, payload), true);
    }

    @PreDestroy
    synchronized void shutdown() {
        if (end != null && !end.isDone()) {
            cancel();
            emit(EventType.DEMO_RESET);
        }
    }
}
