package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.DTOIn.ReviewRequestDTOIn;
import com.example.hadaq.DTOOut.V2Out;
import com.example.hadaq.Entity.Finding;
import com.example.hadaq.Entity.Inspection;
import com.example.hadaq.Entity.WorkOrder;
import com.example.hadaq.Enum.InspectionStatus;
import com.example.hadaq.Enum.ReviewStatus;
import com.example.hadaq.Enum.Role;
import com.example.hadaq.Enum.WorkOrderStatus;
import com.example.hadaq.Mapper.V2Mapper;
import com.example.hadaq.Repository.CaptureRepository;
import com.example.hadaq.Repository.FindingRepository;
import com.example.hadaq.Repository.InspectionRepository;
import com.example.hadaq.Repository.WorkOrderRepository;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Roles;
import com.example.hadaq.Util.Times;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * Inspections (one wall in one survey), the conservator's review of each finding and the approval.
 * An inspection is approved only when no finding is pending (409 otherwise). On approval each work order becomes
 * approved, or rejected when every finding it treats was rejected (ours: the contract leaves this to the core API).
 */
@Service
@RequiredArgsConstructor
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final CaptureRepository captureRepository;
    private final FindingRepository findingRepository;
    private final WorkOrderRepository workOrderRepository;
    private final RegistryService registryService;
    private final LiveService liveService;

    // the open inspection a new capture joins (created on the first capture of the wall in that survey)
    public synchronized Inspection openFor(String surveyId, String buildingId, String wallId) {
        return (surveyId == null
                ? inspectionRepository.findFirstByBuildingIdAndWallIdAndSurveyIdIsNullAndStatusOrderByStartedAtDesc(buildingId, wallId, InspectionStatus.OPEN)
                : inspectionRepository.findFirstByBuildingIdAndWallIdAndSurveyIdAndStatusOrderByStartedAtDesc(buildingId, wallId, surveyId, InspectionStatus.OPEN))
                .orElseGet(() -> {
                    Inspection inspection = new Inspection();
                    inspection.setInspectionId(Ids.newId("ins"));
                    inspection.setSurveyId(surveyId);
                    inspection.setBuildingId(buildingId);
                    inspection.setWallId(wallId);
                    inspection.setStartedAt(Times.now());
                    inspection.setStatus(InspectionStatus.OPEN);
                    return inspectionRepository.save(inspection);
                });
    }

    public Inspection find(String inspectionId) {
        return inspectionRepository.findById(inspectionId).orElseThrow(() -> ApiException.notFound("Unknown inspection " + inspectionId));
    }

    public V2Out.Inspection get(String inspectionId) {
        return toDTO(find(inspectionId));
    }

    // newest first
    public List<V2Out.Inspection> forBuilding(String buildingId) {
        registryService.check(buildingId);
        return inspectionRepository.findByBuildingIdOrderByStartedAtDesc(buildingId).stream().map(this::toDTO).toList();
    }

    public V2Out.Inspection toDTO(Inspection inspection) {
        String id = inspection.getInspectionId();
        return V2Mapper.toDTO(inspection, captureRepository.findByInspectionIdOrderByCapturedAtAsc(id),
                findingRepository.findByInspectionIdOrderByCreatedAtAsc(id), workOrderRepository.findByInspectionIdOrderByCreatedAtAsc(id));
    }

    // findings of the wall's last approved inspection, for change detection in the AI worker
    public List<V2Out.Finding> previousFindings(String buildingId, String wallId) {
        return inspectionRepository.findFirstByBuildingIdAndWallIdAndStatusOrderByApprovedAtDesc(buildingId, wallId, InspectionStatus.APPROVED)
                .map(i -> findingRepository.findByInspectionIdOrderByCreatedAtAsc(i.getInspectionId()).stream()
                        .filter(f -> f.getReviewStatus() != ReviewStatus.REJECTED)
                        .map(V2Mapper::toDTO).toList())
                .orElse(List.of());
    }

    // PATCH /api/v2/findings/{id}/review (conservator only)
    public V2Out.Finding review(String findingId, ReviewRequestDTOIn request, String user) {
        Role role = Roles.require(user, Role.CONSERVATOR);
        Finding finding = findingRepository.findById(findingId).orElseThrow(() -> ApiException.notFound("Unknown finding " + findingId));
        if (finding.getInspectionId() != null && find(finding.getInspectionId()).getStatus() == InspectionStatus.APPROVED) {
            throw new ApiException(409, "The inspection is already approved; its findings cannot be reviewed again");
        }
        boolean corrected = request.status() == ReviewStatus.CORRECTED;
        finding.setReviewStatus(request.status());
        finding.setReviewBy(role.value());
        finding.setReviewAt(Times.now());
        finding.setCorrectedType(corrected ? request.correctedType() : null);
        finding.setCorrectedSeverity(corrected ? request.correctedSeverity() : null);
        finding.setReviewNote(request.note());
        findingRepository.save(finding);

        Map<String, Object> live = new LinkedHashMap<>();
        live.put("finding_id", finding.getFindingId());
        live.put("inspection_id", finding.getInspectionId());
        live.put("review", finding.review());
        liveService.broadcast(LiveService.REVIEW, live);
        return V2Mapper.toDTO(finding);
    }

    // POST /api/v2/inspections/{id}/approve (conservator only); 409 while any finding is pending
    public V2Out.Inspection approve(String inspectionId, String user) {
        Role role = Roles.require(user, Role.CONSERVATOR);
        Inspection inspection = find(inspectionId);
        if (inspection.getStatus() == InspectionStatus.APPROVED) {
            throw new ApiException(409, "The inspection is already approved");
        }
        List<Finding> findings = findingRepository.findByInspectionIdOrderByCreatedAtAsc(inspectionId);
        long pending = findings.stream().filter(f -> f.getReviewStatus() == ReviewStatus.PENDING).count();
        if (pending > 0) {
            throw new ApiException(409, pending + " finding(s) still pending review");
        }
        inspection.setStatus(InspectionStatus.APPROVED);
        inspection.setApprovedBy(role.value());
        inspection.setApprovedAt(Times.now());
        inspectionRepository.save(inspection);

        Set<String> rejected = findings.stream().filter(f -> f.getReviewStatus() == ReviewStatus.REJECTED)
                .map(Finding::getFindingId).collect(Collectors.toSet());
        for (WorkOrder order : workOrderRepository.findByInspectionIdOrderByCreatedAtAsc(inspectionId)) {
            order.setStatus(rejected.containsAll(order.getFindingIds()) ? WorkOrderStatus.REJECTED : WorkOrderStatus.APPROVED);
            workOrderRepository.save(order);
            liveService.broadcast(LiveService.WORK_ORDER, V2Mapper.toDTO(order));
        }
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("inspection_id", inspectionId);
        live.put("status", InspectionStatus.APPROVED);
        liveService.broadcast(LiveService.INSPECTION, live);
        return toDTO(inspection);
    }
}
