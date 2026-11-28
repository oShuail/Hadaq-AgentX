package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.DTOIn.DetectionBatchDTOIn;
import com.example.hadaq.DTOIn.DetectionDTOIn;
import com.example.hadaq.DTOOut.DetectionDTOOut;
import com.example.hadaq.Entity.Detection;
import com.example.hadaq.Entity.Photo;
import com.example.hadaq.Enum.DetectionChange;
import com.example.hadaq.Enum.EventSource;
import com.example.hadaq.Enum.EventType;
import com.example.hadaq.Enum.PhotoStatus;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Mapper.DTOMapper;
import com.example.hadaq.Repository.DetectionRepository;
import com.example.hadaq.Repository.PhotoRepository;
import com.example.hadaq.Util.Times;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DetectionService {

    public static final String MOCK_DETECTOR = "mock-detector";

    private final DetectionRepository detectionRepository;
    private final PhotoRepository photoRepository;
    private final LiveService liveService;
    private final RiskService riskService;
    private final EventService eventService;

    /*
     * POST /detections from the vision worker: store them, mark the photo processed, push them live,
     * drop the cached risk, and emit detection_ready (the agents look at it when something is new or grown)
     */
    public int saveBatch(DetectionBatchDTOIn batch) {
        List<Detection> saved = store(batch);
        List<DetectionDTOOut> data = saved.stream().map(DTOMapper::toDTO).toList();

        Map<String, Object> live = new LinkedHashMap<>();
        live.put("photo_id", batch.photoId());
        live.put("camera_id", batch.cameraId());
        live.put("detections", data);
        liveService.broadcast(LiveService.DETECTION, live);
        riskService.invalidate(batch.buildingId());

        List<Map<String, Object>> changed = saved.stream()
                .filter(d -> d.getChange() == DetectionChange.NEW || d.getChange() == DetectionChange.GROWN)
                .map(d -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("detection_id", d.getDetectionId());
                    c.put("zone_id", d.getZoneId());
                    c.put("cls", d.getCls());
                    c.put("change", d.getChange());
                    c.put("growth_pct", d.getGrowthPct());
                    return c;
                }).toList();
        boolean synthetic = MOCK_DETECTOR.equals(batch.model())
                || (!saved.isEmpty() && saved.stream().allMatch(d -> d.getProvenance() == Provenance.SYNTHETIC));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("photo_id", batch.photoId());
        payload.put("count", saved.size());
        payload.put("changed", changed);
        payload.put("model", batch.model());
        eventService.handle(EventService.create(EventType.DETECTION_READY, batch.buildingId(), EventSource.VISION,
                synthetic ? Provenance.SYNTHETIC : Provenance.MODEL_OUTPUT, payload), true);
        return saved.size();
    }

    // same detection_id again (worker restart) = update, not a duplicate
    private List<Detection> store(DetectionBatchDTOIn batch) {
        Instant now = Times.now();
        List<Detection> saved = detectionRepository.saveAll(
                batch.detections().stream().map((DetectionDTOIn d) -> DTOMapper.toEntity(d, now)).toList());
        photoRepository.findById(batch.photoId()).ifPresent((Photo photo) -> {
            photo.setStatus(PhotoStatus.PROCESSED);
            photoRepository.save(photo);
        });
        return saved;
    }

    public List<DetectionDTOOut> latest(String buildingId) {
        return detectionRepository.findLatestSet(buildingId).stream().map(DTOMapper::toDTO).toList();
    }

    public List<Detection> latestEntities(String buildingId) {
        return detectionRepository.findLatestSet(buildingId);
    }

    // detections of the last processed photo from the same camera (for change detection in vision)
    public List<DetectionDTOOut> previous(String photoId) {
        Photo photo = photoRepository.findById(photoId).orElseThrow(() -> ApiException.notFound("Photo not found"));
        List<Detection> rows = detectionRepository
                .findTop200ByBuildingIdAndPhotoIdNotOrderByCreatedAtDesc(photo.getBuildingId(), photoId)
                .stream().filter(d -> photo.getCameraId().equals(d.getCameraId())).toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        String lastPhoto = rows.get(0).getPhotoId();
        return rows.stream().filter(d -> d.getPhotoId().equals(lastPhoto)).map(DTOMapper::toDTO).toList();
    }
}
