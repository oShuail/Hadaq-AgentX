package com.example.hadaq.Demo;

import com.example.hadaq.Config.ExecutorConfig;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Events.HadaqEventStored;
import com.example.hadaq.Service.PhotoService;
import com.example.hadaq.Service.RegistryService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;

/*
 * Built-in fixed camera B01-CAM01 (port of hadaq_vision.camera_sim, owner: Abdurhmn). Plays the "baseline" series in
 * a loop; on new_crack it switches to "damage" and moves one frame per interval (staying on the last);
 * demo_reset goes back to baseline. Each frame is uploaded like POST /photos (faces checked) with provenance synthetic.
 */
@Slf4j
@Service
public class DemoCamera {

    public static final String CAMERA_ID = "B01-CAM01";

    private final PhotoService photoService;
    private final RegistryService registryService;
    private final TaskScheduler scheduler;
    private final boolean enabled;
    private final double intervalSeconds;

    private DemoFrames frames;
    private ScheduledFuture<?> task;
    private String mode = "baseline";
    private int index;

    public DemoCamera(PhotoService photoService, RegistryService registryService,
                      @Qualifier(ExecutorConfig.SCHEDULER) TaskScheduler scheduler,
                      @Value("${hadaq.demo.enabled:true}") boolean demoEnabled,
                      @Value("${hadaq.demo.camera:true}") boolean camera,
                      @Value("${hadaq.demo.camera-interval-seconds:15}") double intervalSeconds) {
        this.photoService = photoService;
        this.registryService = registryService;
        this.scheduler = scheduler;
        this.enabled = demoEnabled && camera;
        this.intervalSeconds = intervalSeconds;
    }

    public synchronized void start() {
        if (!enabled || task != null) {
            return;
        }
        try {
            frames = new DemoFrames();
        } catch (Exception exception) {
            log.warn("Demo camera not started: could not draw the frames ({})", exception.getMessage());
            return;
        }
        task = scheduler.scheduleAtFixedRate(this::uploadNext, Duration.ofMillis((long) (intervalSeconds * 1000)));
        log.info("Demo camera {}: a synthetic frame every {} s", CAMERA_ID, intervalSeconds);
    }

    void uploadNext() {
        String sequence;
        int frame;
        byte[] data;
        synchronized (this) {
            sequence = mode;
            if (mode.equals("damage")) {
                frame = Math.min(index, DemoFrames.DAMAGE_FRAMES - 1) + 1;  // stay on the last damage frame
                data = frames.damage(frame);
            } else {
                frame = index % DemoFrames.BASELINE_FRAMES + 1;
                data = frames.baseline(frame);
            }
            index++;
        }
        try {
            String name = String.format(Locale.ROOT, "frame_%03d.jpg", frame);
            photoService.store(data, name, CAMERA_ID, registryService.buildingId(), null, Provenance.SYNTHETIC, sequence, frame);
        } catch (Exception exception) {
            log.warn("Demo camera upload failed: {}", exception.getMessage());
        }
    }

    @EventListener
    public synchronized void onEvent(HadaqEventStored stored) {
        switch (stored.event().type()) {
            case NEW_CRACK -> {
                mode = "damage";
                index = 0;
            }
            case DEMO_RESET -> {
                mode = "baseline";
                index = 0;
            }
            default -> {
                return;
            }
        }
        log.info("Demo camera: {} -> sequence {}", stored.event().type().value(), mode);
    }

    @PreDestroy
    synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }
}
