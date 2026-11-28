package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.Enum.InspectionStatus;
import com.example.hadaq.Repository.CaptureRepository;
import com.example.hadaq.Repository.FindingRepository;
import com.example.hadaq.Repository.InspectionRepository;
import com.example.hadaq.Repository.SurveyRepository;
import com.example.hadaq.Repository.WorkOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/*
 * Demo reset (T4) while the core API runs: POST /api/v2/demo/reset. Empties the v2 demo data (work orders, findings,
 * captures, inspections, surveys) and the uploaded capture files, keeps the registry, and seeds the first survey again,
 * so every demo starts the same. Only when DEMO_ENABLED=true. With the API stopped: tools/demo_reset.sh does the same.
 */
@Slf4j
@Service
public class DemoResetService {

    private final WorkOrderRepository workOrderRepository;
    private final FindingRepository findingRepository;
    private final CaptureRepository captureRepository;
    private final InspectionRepository inspectionRepository;
    private final SurveyRepository surveyRepository;
    private final SurveyService surveyService;
    private final LiveService liveService;
    private final TransactionTemplate transactions;
    private final boolean demoEnabled;
    private final Path captureDir;

    public DemoResetService(WorkOrderRepository workOrderRepository, FindingRepository findingRepository,
                            CaptureRepository captureRepository, InspectionRepository inspectionRepository,
                            SurveyRepository surveyRepository, SurveyService surveyService, LiveService liveService,
                            TransactionTemplate transactions,
                            @Value("${hadaq.demo.enabled:true}") boolean demoEnabled,
                            @Value("${hadaq.capture-dir:./data/captures}") String captureDir) {
        this.workOrderRepository = workOrderRepository;
        this.findingRepository = findingRepository;
        this.captureRepository = captureRepository;
        this.inspectionRepository = inspectionRepository;
        this.surveyRepository = surveyRepository;
        this.surveyService = surveyService;
        this.liveService = liveService;
        this.transactions = transactions;
        this.demoEnabled = demoEnabled;
        this.captureDir = Path.of(captureDir);
    }

    public Map<String, Object> reset() {
        if (!demoEnabled) {
            throw new ApiException(403, "Demo reset is off (DEMO_ENABLED=false)");
        }
        Map<String, Object> cleared = new LinkedHashMap<>();
        transactions.executeWithoutResult(tx -> {
            cleared.put("work_orders", workOrderRepository.count());
            cleared.put("findings", findingRepository.count());
            cleared.put("captures", captureRepository.count());
            cleared.put("inspections", inspectionRepository.count());
            cleared.put("surveys", surveyRepository.count());
            workOrderRepository.deleteAllInBatch();
            findingRepository.deleteAllInBatch();
            captureRepository.deleteAllInBatch();
            inspectionRepository.deleteAllInBatch();
            surveyRepository.deleteAllInBatch();
        });
        cleared.put("files", deleteCaptureFiles());
        surveyService.seed();  // the first survey, with no shots

        // tell open dashboards to reload (the site list and inspections listen to this channel)
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("inspection_id", null);
        live.put("status", InspectionStatus.OPEN);
        live.put("reset", true);
        liveService.broadcast(LiveService.INSPECTION, live);
        log.info("Demo reset: {}", cleared);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reset", true);
        out.put("cleared", cleared);
        out.put("surveys", surveyService.list(null));
        return out;
    }

    // only plain files directly in the capture folder: nothing outside it is touched
    private int deleteCaptureFiles() {
        if (!Files.isDirectory(captureDir)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> files = Files.list(captureDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.deleteIfExists(file);
                deleted++;
            }
        } catch (IOException exception) {
            log.warn("Demo reset could not delete every capture file: {}", exception.getMessage());
        }
        return deleted;
    }
}
