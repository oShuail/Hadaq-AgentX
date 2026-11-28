package com.example.hadaq.Demo;

import com.example.hadaq.Config.ExecutorConfig;
import com.example.hadaq.DTOIn.DetectionBatchDTOIn;
import com.example.hadaq.DTOIn.DetectionDTOIn;
import com.example.hadaq.DTOOut.DetectionDTOOut;
import com.example.hadaq.DTOOut.PhotoDTOOut;
import com.example.hadaq.Enum.DefectClass;
import com.example.hadaq.Enum.DetectionChange;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Model.WallSpec;
import com.example.hadaq.Service.DetectionService;
import com.example.hadaq.Service.PhotoService;
import com.example.hadaq.Service.RegistryService;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Numbers;
import com.example.hadaq.Util.ZoneIds;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/*
 * Built-in mock vision worker (port of hadaq_vision.worker with MockDetector, zones and change, owner: Abdurhmn).
 * Picks up new photos, reports deterministic fake detections (a crack at R2C5 that grows 15% per damage frame, the
 * older damage drawn by DemoFrames, a plaster loss from damage frame 2), maps them to zones, labels new / grown /
 * unchanged against the previous photo, and posts the batch like POST /detections. Output is labelled synthetic.
 * Turn it off (hadaq.demo.vision=false) when the real RF-DETR worker runs.
 */
@Slf4j
@Service
public class MockVisionWorker {

    public static final String MODEL = "mock-detector";
    private static final double GROWTH_THRESHOLD_PCT = 10.0;

    record Raw(DefectClass cls, double confidence, double[] box, Double areaPx, Double lengthPx) {
    }

    private final PhotoService photoService;
    private final DetectionService detectionService;
    private final RegistryService registryService;
    private final TaskScheduler scheduler;
    private final boolean enabled;
    private final double intervalSeconds;
    private ScheduledFuture<?> task;

    public MockVisionWorker(PhotoService photoService, DetectionService detectionService, RegistryService registryService,
                            @Qualifier(ExecutorConfig.SCHEDULER) TaskScheduler scheduler,
                            @Value("${hadaq.demo.enabled:true}") boolean demoEnabled,
                            @Value("${hadaq.demo.vision:true}") boolean vision,
                            @Value("${hadaq.demo.vision-interval-seconds:2}") double intervalSeconds) {
        this.photoService = photoService;
        this.detectionService = detectionService;
        this.registryService = registryService;
        this.scheduler = scheduler;
        this.enabled = demoEnabled && vision;
        this.intervalSeconds = intervalSeconds;
    }

    public synchronized void start() {
        if (!enabled || task != null) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(this::poll, Duration.ofMillis((long) (intervalSeconds * 1000)));
        log.info("Mock vision worker running ({}, synthetic)", MODEL);
    }

    void poll() {
        try {
            for (PhotoDTOOut photo : photoService.list(registryService.buildingId(), "new", 20)) {
                DetectionBatchDTOIn batch = process(photo);
                if (batch != null) {
                    detectionService.saveBatch(batch);
                    log.info("{}: {} detections", photo.photoId(), batch.detections().size());
                }
            }
        } catch (Exception exception) {
            log.warn("Mock vision round failed: {}", exception.getMessage());
        }
    }

    DetectionBatchDTOIn process(PhotoDTOOut photo) {
        RegistryService.CameraSpec camera = registryService.camera(photo.cameraId()).orElse(null);
        if (camera == null) {
            return null;  // a camera without a registry entry cannot be mapped to the wall
        }
        WallSpec wall = registryService.wall(camera.wallId()).orElseThrow();
        boolean damaged = "damage".equals(photo.sequence());
        int frame = photo.frame() == null ? 1 : photo.frame();

        List<Map<String, Object>> current = new ArrayList<>();
        for (Raw raw : detect(camera.wallBboxPx(), damaged, frame)) {
            double cx = (raw.box()[0] + raw.box()[2]) / 2, cy = (raw.box()[1] + raw.box()[3]) / 2;
            String zone = pixelToZone(cx, cy, camera.wallBboxPx(), photo.buildingId(), wall);
            if (zone == null) {
                continue;
            }
            Map<String, Object> d = new HashMap<>();
            d.put("zone", zone);
            d.put("raw", raw);
            current.add(d);
        }
        labelChanges(current, detectionService.previous(photo.photoId()));

        List<DetectionDTOIn> detections = new ArrayList<>();
        for (Map<String, Object> d : current) {
            Raw raw = (Raw) d.get("raw");
            detections.add(new DetectionDTOIn(Ids.newId("det"), photo.photoId(), photo.cameraId(), photo.buildingId(),
                    (String) d.get("zone"), raw.cls(), raw.confidence(),
                    List.of(raw.box()[0], raw.box()[1], raw.box()[2], raw.box()[3]), null, raw.lengthPx(), raw.areaPx(),
                    null, null, MODEL, Provenance.SYNTHETIC, (DetectionChange) d.get("change"), (Double) d.get("growth")));
        }
        return new DetectionBatchDTOIn(photo.photoId(), photo.buildingId(), photo.cameraId(), MODEL, detections);
    }

    // MockDetector.detect: frame N of the damage series shows a crack that grows 15% per frame
    static List<Raw> detect(double[] bbox, boolean damaged, int frame) {
        double x0 = bbox[0], y0 = bbox[1], x1 = bbox[2], y1 = bbox[3];
        double cx = x0 + (4.5 / 8) * (x1 - x0);  // W01 R2C5
        double cy = y0 + (2.5 / 4) * (y1 - y0);
        double scale = (y1 - y0) / 3000;
        double length = 300.0 * scale * (damaged ? Math.pow(1.15, frame) : 1.0);
        List<Raw> out = new ArrayList<>();
        out.add(new Raw(DefectClass.CRACK, 0.83, new double[]{cx - 20 * scale, cy - length / 2, cx + 20 * scale, cy + length / 2},
                length * 6, length));
        // older damage in every frame (DemoFrames.STATIC_DAMAGE), as fractions of the generated wall box
        Object[][] statics = {
                {DefectClass.MISSING_MATERIAL, 0.66, "holes"},         // R3C2 -> holes and burrows
                {DefectClass.EROSION, 0.74, "gullies"},                // R3C6 -> rain gullies
                {DefectClass.MOISTURE_STAIN, 0.69, "salt"},            // R1C7 -> salt efflorescence
                {DefectClass.MISSING_MATERIAL, 0.78, "parapet"},       // R4C7 -> parapet loss
                {DefectClass.EROSION, 0.81, "base_erosion"}};          // R1C4 -> base erosion
        int[] demo = DemoFrames.WALL_BBOX;
        for (Object[] s : statics) {
            int[] px = DemoFrames.STATIC_DAMAGE.get((String) s[2]);
            double[] box = {
                    x0 + (px[0] - demo[0]) / (double) (demo[2] - demo[0]) * (x1 - x0),
                    y0 + (px[1] - demo[1]) / (double) (demo[3] - demo[1]) * (y1 - y0),
                    x0 + (px[2] - demo[0]) / (double) (demo[2] - demo[0]) * (x1 - x0),
                    y0 + (px[3] - demo[1]) / (double) (demo[3] - demo[1]) * (y1 - y0)};
            out.add(new Raw((DefectClass) s[0], (double) s[1], box, (box[2] - box[0]) * (box[3] - box[1]),
                    Math.max(box[2] - box[0], box[3] - box[1])));
        }
        if (damaged && frame >= 2) {
            double[] box = {x0 + .13 * (x1 - x0), y0 + .83 * (y1 - y0), x0 + .25 * (x1 - x0), y0 + .96 * (y1 - y0)};
            out.add(new Raw(DefectClass.PLASTER_LOSS, 0.71, box, (box[2] - box[0]) * (box[3] - box[1]), null));
        }
        return out;
    }

    // fixed camera looking straight at the wall: R1 is the bottom of the photo
    static String pixelToZone(double cx, double cy, double[] bbox, String buildingId, WallSpec wall) {
        double x0 = bbox[0], y0 = bbox[1], x1 = bbox[2], y1 = bbox[3];
        if (cx < x0 || cx > x1 || cy < y0 || cy > y1) {
            return null;
        }
        int col = Math.min(wall.cols(), (int) ((cx - x0) / (x1 - x0) * wall.cols()) + 1);
        int rowFromTop = Math.min(wall.rows(), (int) ((cy - y0) / (y1 - y0) * wall.rows()) + 1);
        return ZoneIds.zoneId(buildingId, wall.id(), wall.rows() - rowFromTop + 1, col);
    }

    // change detection v0: compare with the previous photo's detections per zone and class
    static void labelChanges(List<Map<String, Object>> current, List<DetectionDTOOut> previous) {
        Map<String, Double> before = new HashMap<>();
        for (DetectionDTOOut d : previous) {
            double size = d.lengthPx() != null && d.lengthPx() != 0 ? d.lengthPx() : d.areaPx() != null ? d.areaPx() : 0.0;
            before.merge(d.zoneId() + "|" + d.cls().value(), size, Math::max);
        }
        for (Map<String, Object> d : current) {
            Raw raw = (Raw) d.get("raw");
            Double old = before.get(d.get("zone") + "|" + raw.cls().value());
            if (old == null || old == 0) {
                d.put("change", DetectionChange.NEW);
                d.put("growth", null);
                continue;
            }
            double size = raw.lengthPx() != null && raw.lengthPx() != 0 ? raw.lengthPx() : raw.areaPx() != null ? raw.areaPx() : 0.0;
            double growth = (size - old) / old * 100.0;
            d.put("growth", Numbers.round(growth, 1));
            d.put("change", growth >= GROWTH_THRESHOLD_PCT ? DetectionChange.GROWN : DetectionChange.UNCHANGED);
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
