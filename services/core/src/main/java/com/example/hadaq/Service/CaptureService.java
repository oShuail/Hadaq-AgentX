package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.Config.ExecutorConfig;
import com.example.hadaq.DTOIn.CaptureMetaDTOIn;
import com.example.hadaq.DTOIn.CaptureResultsDTOIn;
import com.example.hadaq.DTOIn.FindingDTOIn;
import com.example.hadaq.DTOIn.WorkOrderDTOIn;
import com.example.hadaq.DTOOut.V2Out;
import com.example.hadaq.Entity.Capture;
import com.example.hadaq.Entity.Finding;
import com.example.hadaq.Entity.Inspection;
import com.example.hadaq.Entity.WorkOrder;
import com.example.hadaq.Enum.CaptureStatus;
import com.example.hadaq.Enum.Escalation;
import com.example.hadaq.Enum.InspectionStatus;
import com.example.hadaq.Mapper.V2Mapper;
import com.example.hadaq.Model.PrivacyResult;
import com.example.hadaq.Repository.CaptureRepository;
import com.example.hadaq.Repository.FindingRepository;
import com.example.hadaq.Repository.WorkOrderRepository;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Times;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/*
 * v2 captures: the surveyor app uploads a photo (POST /api/v2/captures), the core API stores it (faces hidden when the
 * phone could not), joins it to the wall's open inspection, marks the survey shot, and sends it to the AI worker.
 * The worker posts CaptureResults back (POST /api/v2/captures/{id}/results): findings and work-order drafts for review.
 * hadaq.ai.mode: remote = the AI worker at hadaq.ai.url; mock = the built-in MockAiWorker (synthetic results).
 */
@Slf4j
@Service
public class CaptureService {

    public record CaptureFile(FileSystemResource resource, MediaType mediaType) {
    }

    private final CaptureRepository captureRepository;
    private final FindingRepository findingRepository;
    private final WorkOrderRepository workOrderRepository;
    private final InspectionService inspectionService;
    private final SurveyService surveyService;
    private final V2RegistryService v2RegistryService;
    private final PrivacyService privacyService;
    private final LiveService liveService;
    private final AiWorkerClient aiWorkerClient;
    private final MockAiWorker mockAiWorker;
    private final JsonMapper jsonMapper;
    private final Validator validator;
    private final TaskExecutor worker;
    private final TransactionTemplate transactions;
    private final Path captureDir;
    private final String publicUrl;
    private final boolean privacyBlur;
    private final boolean mockAi;

    public CaptureService(CaptureRepository captureRepository, FindingRepository findingRepository,
                          WorkOrderRepository workOrderRepository, InspectionService inspectionService,
                          SurveyService surveyService, V2RegistryService v2RegistryService, PrivacyService privacyService,
                          LiveService liveService, AiWorkerClient aiWorkerClient, MockAiWorker mockAiWorker,
                          JsonMapper jsonMapper, Validator validator,
                          @Qualifier(ExecutorConfig.WORKER) TaskExecutor worker, TransactionTemplate transactions,
                          @Value("${hadaq.capture-dir:./data/captures}") String captureDir,
                          @Value("${hadaq.public-url:http://localhost:8080}") String publicUrl,
                          @Value("${hadaq.privacy-blur:true}") boolean privacyBlur,
                          @Value("${hadaq.ai.mode:mock}") String aiMode) throws IOException {
        this.captureRepository = captureRepository;
        this.findingRepository = findingRepository;
        this.workOrderRepository = workOrderRepository;
        this.inspectionService = inspectionService;
        this.surveyService = surveyService;
        this.v2RegistryService = v2RegistryService;
        this.privacyService = privacyService;
        this.liveService = liveService;
        this.aiWorkerClient = aiWorkerClient;
        this.mockAiWorker = mockAiWorker;
        this.jsonMapper = jsonMapper;
        this.validator = validator;
        this.worker = worker;
        this.transactions = transactions;
        this.captureDir = Path.of(captureDir);
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.privacyBlur = privacyBlur;
        this.mockAi = "mock".equalsIgnoreCase(aiMode.trim());
        Files.createDirectories(this.captureDir);
        log.info("v2 captures go to the {} AI worker{}", mockAi ? "built-in mock" : "remote", mockAi ? "" : " at " + aiWorkerClient.url());
    }

    // ---------------- upload ----------------

    // POST /api/v2/captures: 202 CaptureAccepted; the same client_uuid again returns the first capture with duplicate = true
    public V2Out.CaptureAccepted accept(MultipartFile file, String metaJson) throws IOException {
        CaptureMetaDTOIn meta = parse(metaJson, CaptureMetaDTOIn.class, "meta");
        V2Out.Wall wall = v2RegistryService.wall(meta.buildingId(), meta.wallId());  // 404 unknown building, 400 unknown wall
        var existing = captureRepository.findByClientUuid(meta.clientUuid());
        if (existing.isPresent()) {
            return new V2Out.CaptureAccepted(existing.get().getCaptureId(), existing.get().getStatus(), true);
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException("file is required (the JPEG of the wall)");
        }

        byte[] data = file.getBytes();
        String suffix = suffixOf(file.getOriginalFilename());
        Integer facesCore = null;
        // the phone hides faces when it can; when it did not check (faces_hidden = null) the core API does, before storing
        if (privacyBlur && meta.facesHidden() == null) {
            PrivacyResult privacy = privacyService.protect(data, suffix);
            data = privacy.data();
            if (privacy.checked()) {
                facesCore = privacy.facesHidden();
                suffix = ".png".equals(suffix) ? suffix : ".jpg";
            }
        }

        Inspection inspection = inspectionService.openFor(meta.surveyId(), meta.buildingId(), wall.id());
        Capture capture = new Capture();
        capture.setCaptureId(Ids.newId("cap"));
        capture.setClientUuid(meta.clientUuid());
        capture.setSurveyId(meta.surveyId());
        capture.setInspectionId(inspection.getInspectionId());
        capture.setBuildingId(meta.buildingId());
        capture.setWallId(wall.id());
        capture.setSegment(meta.segment());
        capture.setCapturedAt(Times.parse(meta.capturedAt()));
        capture.setSurveyor(meta.surveyor());
        capture.setDevice(meta.device());
        capture.setSource(meta.source());
        capture.setGps(meta.gps());
        capture.setHeadingDeg(meta.headingDeg());
        capture.setTiltDeg(meta.tiltDeg());
        capture.setQuality(meta.quality());
        capture.setCornersPx(meta.cornersPx());
        capture.setImageWidthPx(meta.imageWidthPx());
        capture.setImageHeightPx(meta.imageHeightPx());
        capture.setFacesHidden(meta.facesHidden());
        capture.setFacesHiddenCore(facesCore);
        capture.setFacesHiddenServer(facesCore);
        capture.setProvenance(meta.provenance());
        capture.setStatus(CaptureStatus.UPLOADED);
        capture.setCreatedAt(Times.now());
        Path path = captureDir.resolve(capture.getCaptureId() + suffix);
        Files.write(path, data);
        capture.setFilePath(path.toString());
        capture.setFileUrl(publicUrl + "/api/v2/captures/" + capture.getCaptureId() + "/file");
        try {
            captureRepository.saveAndFlush(capture);
        } catch (DataIntegrityViolationException sameUuid) {
            // the phone retried while the first upload was still being stored
            Files.deleteIfExists(path);
            Capture first = captureRepository.findByClientUuid(meta.clientUuid()).orElseThrow(() -> sameUuid);
            return new V2Out.CaptureAccepted(first.getCaptureId(), first.getStatus(), true);
        }
        surveyService.markShot(meta.surveyId(), meta.buildingId(), wall.id(), meta.segment());
        liveService.broadcast(LiveService.CAPTURE, V2Mapper.toDTO(capture));
        dispatch(capture.getCaptureId());
        return new V2Out.CaptureAccepted(capture.getCaptureId(), CaptureStatus.UPLOADED, false);
    }

    // POST /api/v2/captures/{id}/retry: only a failed capture goes to the AI worker again
    public V2Out.Capture retry(String captureId) {
        Capture capture = find(captureId);
        if (capture.getStatus() != CaptureStatus.FAILED) {
            throw new ApiException(409, "Only a failed capture can be retried (this one is " + capture.getStatus().value() + ")");
        }
        capture.setError(null);
        capture.setStatus(CaptureStatus.UPLOADED);
        captureRepository.save(capture);
        dispatch(captureId);
        return V2Mapper.toDTO(capture);
    }

    // send to the AI worker in the background: the upload answers at once
    private void dispatch(String captureId) {
        worker.execute(() -> {
            Capture capture = find(captureId);
            setStatus(capture, CaptureStatus.PROCESSING, null);
            List<V2Out.Finding> previous = inspectionService.previousFindings(capture.getBuildingId(), capture.getWallId());
            try {
                if (mockAi) {
                    applyResults(captureId, mockAiWorker.process(capture, previous), null, null);
                } else {
                    aiWorkerClient.process(new V2Out.ProcessRequest(captureId, capture.getFileUrl(),
                            publicUrl + "/api/v2/captures/" + captureId + "/results",
                            v2RegistryService.wall(capture.getBuildingId(), capture.getWallId()),
                            capture.getCornersPx(), previous));
                }
            } catch (Exception exception) {
                log.warn("Capture {} could not be analysed: {}", captureId, exception.getMessage());
                setStatus(find(captureId), CaptureStatus.FAILED, mockAi
                        ? "Mock AI worker failed: " + exception.getMessage()
                        : "AI worker not reachable at " + aiWorkerClient.url() + ": " + exception.getMessage());
            }
        });
    }

    private void setStatus(Capture capture, CaptureStatus status, String error) {
        capture.setStatus(status);
        capture.setError(error);
        captureRepository.save(capture);
        liveService.broadcast(LiveService.CAPTURE, V2Mapper.toDTO(capture));
    }

    // ---------------- results from the AI worker ----------------

    // POST /api/v2/captures/{id}/results (multipart: results JSON + rectified JPEG, optional)
    public V2Out.Capture results(String captureId, String resultsJson, MultipartFile rectified) throws IOException {
        CaptureResultsDTOIn results = parse(resultsJson, CaptureResultsDTOIn.class, "results");
        return applyResults(captureId, results, rectified == null || rectified.isEmpty() ? null : rectified.getBytes(),
                rectified == null ? null : rectified.getOriginalFilename());
    }

    public V2Out.Capture applyResults(String captureId, CaptureResultsDTOIn results, byte[] rectified, String rectifiedName) throws IOException {
        Capture capture = find(captureId);
        if (!captureId.equals(results.captureId())) {
            throw new ApiException("results.capture_id " + results.captureId() + " does not match " + captureId);
        }
        if (capture.getInspectionId() != null && inspectionService.find(capture.getInspectionId()).getStatus() == InspectionStatus.APPROVED) {
            throw new ApiException(409, "The inspection of this capture is already approved");
        }
        if (!results.ok()) {
            setStatus(capture, CaptureStatus.FAILED, results.error() == null ? "The AI worker could not analyse the photo" : results.error());
            return V2Mapper.toDTO(capture);
        }
        for (FindingDTOIn f : results.findings()) {
            if (!captureId.equals(f.captureId())) {
                throw new ApiException("finding " + f.findingId() + " belongs to capture " + f.captureId() + ", not " + captureId);
            }
        }
        Set<String> findingIds = new HashSet<>();
        Instant now = Times.now();
        List<Finding> findings = results.findings().stream().map(f -> V2Mapper.toEntity(f, capture, now)).toList();
        findings.forEach(f -> findingIds.add(f.getFindingId()));
        List<WorkOrder> orders = results.workOrders().stream().map(w -> V2Mapper.toEntity(w, capture, now)).toList();
        for (WorkOrder order : orders) {
            if (!findingIds.containsAll(order.getFindingIds())) {
                throw new ApiException("work order " + order.getWorkOrderId() + " names findings that are not in these results");
            }
        }
        // the worker picks the ids: one that already belongs to another capture would overwrite that capture's record
        for (Finding f : findings) {
            findingRepository.findById(f.getFindingId()).filter(old -> !old.getCaptureId().equals(captureId)).ifPresent(old -> {
                throw new ApiException(409, "finding_id " + f.getFindingId() + " already belongs to capture " + old.getCaptureId());
            });
        }
        for (WorkOrder o : orders) {
            workOrderRepository.findById(o.getWorkOrderId()).filter(old -> !captureId.equals(old.getCaptureId())).ifPresent(old -> {
                throw new ApiException(409, "work_order_id " + o.getWorkOrderId() + " already belongs to capture " + old.getCaptureId());
            });
        }
        if (rectified != null) {
            Path path = captureDir.resolve(captureId + "_rectified" + suffixOf(rectifiedName));
            Files.write(path, rectified);
            capture.setRectifiedPath(path.toString());
            capture.setRectifiedUrl(publicUrl + "/api/v2/captures/" + captureId + "/rectified");
        }
        int core = capture.getFacesHiddenCore() == null ? 0 : capture.getFacesHiddenCore();
        capture.setScale(results.scale());
        capture.setPxPerM(results.pxPerM());
        capture.setFacesHiddenServer(core + results.facesHiddenServer());
        capture.setFindingsCount(findings.size());
        capture.setUrgent(findings.stream().anyMatch(f -> f.getEscalation() == Escalation.URGENT));
        capture.setSummaryAr(results.summaryAr());
        capture.setSummaryEn(results.summaryEn());
        capture.setDetector(results.detector());
        capture.setStatus(CaptureStatus.ANALYZED);
        capture.setError(null);

        // replace earlier results of the same capture (a re-analysis), all or nothing
        transactions.executeWithoutResult(tx -> {
            findingRepository.deleteAll(findingRepository.findByCaptureId(captureId));
            workOrderRepository.deleteAll(workOrderRepository.findByCaptureId(captureId));
            findingRepository.flush();
            findingRepository.saveAll(findings);
            workOrderRepository.saveAll(orders);
            captureRepository.save(capture);
        });

        Map<String, Object> live = new LinkedHashMap<>();
        live.put("capture_id", captureId);
        live.put("inspection_id", capture.getInspectionId());
        live.put("count", findings.size());
        live.put("urgent", capture.isUrgent());
        liveService.broadcast(LiveService.FINDING, live);
        orders.forEach(o -> liveService.broadcast(LiveService.WORK_ORDER, V2Mapper.toDTO(o)));
        liveService.broadcast(LiveService.CAPTURE, V2Mapper.toDTO(capture));
        return V2Mapper.toDTO(capture);
    }

    // ---------------- reads ----------------

    public Capture find(String captureId) {
        return captureRepository.findById(captureId).orElseThrow(() -> ApiException.notFound("Unknown capture " + captureId));
    }

    public V2Out.Capture get(String captureId) {
        return V2Mapper.toDTO(find(captureId));
    }

    public CaptureFile file(String captureId, boolean rectified) {
        Capture capture = find(captureId);
        String path = rectified ? capture.getRectifiedPath() : capture.getFilePath();
        if (path == null) {
            throw ApiException.notFound(rectified ? "No rectified image for this capture yet" : "Capture file not found");
        }
        FileSystemResource resource = new FileSystemResource(path);
        if (!resource.exists()) {
            throw ApiException.notFound("Capture file not found");
        }
        return new CaptureFile(resource, MediaTypeFactory.getMediaType(resource).orElse(MediaType.IMAGE_JPEG));
    }

    // ---------------- helpers ----------------

    private <T> T parse(String json, Class<T> type, String part) {
        if (json == null || json.isBlank()) {
            throw new ApiException(part + " is required");
        }
        T value;
        try {
            value = jsonMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new ApiException(part + " is not valid JSON or has invalid values");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ApiException(violations.iterator().next().getMessage());
        }
        return value;
    }

    private static String suffixOf(String filename) {
        String name = filename == null || filename.isBlank() ? "capture.jpg" : filename;
        int dot = name.lastIndexOf('.');
        String suffix = dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
        return suffix.matches("^\\.[a-z0-9]{1,5}$") ? suffix : ".jpg";
    }
}
