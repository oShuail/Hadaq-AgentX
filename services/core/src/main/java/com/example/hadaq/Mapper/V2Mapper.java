package com.example.hadaq.Mapper;

import com.example.hadaq.DTOIn.FindingDTOIn;
import com.example.hadaq.DTOIn.WorkOrderDTOIn;
import com.example.hadaq.DTOOut.V2Out;
import com.example.hadaq.Entity.Capture;
import com.example.hadaq.Entity.Finding;
import com.example.hadaq.Entity.Inspection;
import com.example.hadaq.Entity.Survey;
import com.example.hadaq.Entity.WorkOrder;
import com.example.hadaq.Enum.ReviewStatus;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Times;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// entities <-> v2 DTOs
public final class V2Mapper {

    private V2Mapper() {
    }

    public static V2Out.Survey toDTO(Survey s) {
        return new V2Out.Survey(s.getSurveyId(), s.getTitle(), s.getAssignee(), s.getDue(), s.getTasks());
    }

    public static V2Out.Capture toDTO(Capture c) {
        return new V2Out.Capture(c.getClientUuid(), c.getSurveyId(), c.getBuildingId(), c.getWallId(), c.getSegment(),
                c.getCapturedAt(), c.getSurveyor(), c.getDevice(), c.getSource(), c.getGps(), c.getHeadingDeg(),
                c.getTiltDeg(), c.getQuality(), c.getCornersPx(), c.getImageWidthPx(), c.getImageHeightPx(),
                c.getFacesHidden(), c.getProvenance(), c.getCaptureId(), c.getStatus(), c.getInspectionId(),
                c.getFileUrl(), c.getRectifiedUrl(), c.getPxPerM(), c.getScale(), c.getFacesHiddenServer(),
                c.getFindingsCount(), c.isUrgent(), c.getError(), c.getCreatedAt());
    }

    public static V2Out.Finding toDTO(Finding f) {
        return new V2Out.Finding(f.getFindingId(), f.getCaptureId(), f.getInspectionId(), f.getBuildingId(),
                f.getWallId(), f.getZoneId(), f.getDamageType(), f.getSeverity(), f.getConfidence(), f.isSuspected(),
                f.getBoxPx(), f.getBoxWallM(), f.getPolygonWallM(), f.getLengthCm(), f.getWidthMm(), f.getAreaM2(),
                f.getChange(), f.getPreviousFindingId(), f.getLookalike(), f.getPriority(), f.review(), f.getModel(),
                f.getProvenance(), f.getEvidenceIds());
    }

    public static V2Out.WorkOrder toDTO(WorkOrder w) {
        return new V2Out.WorkOrder(w.getWorkOrderId(), w.getInspectionId(), w.getFindingIds(), w.getLocation(),
                w.getTreatmentId(), w.getStepsAr(), w.getStepsEn(), w.getMaterialsAr(), w.getMaterialsEn(),
                w.getToolsAr(), w.getToolsEn(), w.isFixWaterSourceFirst(), w.getCitation(), w.getEscalation(),
                w.getStatus(), w.getCreatedAt());
    }

    public static V2Out.Inspection toDTO(Inspection i, List<Capture> captures, List<Finding> findings, List<WorkOrder> workOrders) {
        return new V2Out.Inspection(i.getInspectionId(), i.getSurveyId(), i.getBuildingId(), i.getWallId(),
                i.getStartedAt(), captures.stream().map(V2Mapper::toDTO).toList(),
                findings.stream().map(V2Mapper::toDTO).toList(), workOrders.stream().map(V2Mapper::toDTO).toList(),
                i.getStatus(), i.getApprovedBy(), i.getApprovedAt());
    }

    // from the AI worker. The core API decides the capture, inspection, building and wall, and starts the review as pending
    public static Finding toEntity(FindingDTOIn d, Capture capture, Instant now) {
        Finding f = new Finding();
        f.setFindingId(d.findingId() == null || d.findingId().isBlank() ? Ids.newId("fnd") : d.findingId());
        f.setCaptureId(capture.getCaptureId());
        f.setInspectionId(capture.getInspectionId());
        f.setBuildingId(capture.getBuildingId());
        f.setWallId(capture.getWallId());
        f.setZoneId(d.zoneId());
        f.setDamageType(d.damageType());
        f.setSeverity(d.severity());
        f.setConfidence(d.confidence());
        f.setSuspected(d.suspected());
        f.setBoxPx(d.boxPx());
        f.setBoxWallM(d.boxWallM());
        f.setPolygonWallM(d.polygonWallM());
        f.setLengthCm(d.lengthCm());
        f.setWidthMm(d.widthMm());
        f.setAreaM2(d.areaM2());
        f.setChange(d.change());
        f.setPreviousFindingId(d.previousFindingId());
        f.setLookalike(d.lookalike());
        f.setPriority(d.priority());
        f.setEscalation(d.priority() == null ? null : d.priority().escalation());
        f.setReviewStatus(ReviewStatus.PENDING);
        f.setModel(d.model());
        f.setProvenance(d.provenance());
        List<String> evidence = new ArrayList<>(d.evidenceIds());
        if (!evidence.contains(capture.getCaptureId())) {
            evidence.add(0, capture.getCaptureId());
        }
        f.setEvidenceIds(evidence);
        f.setCreatedAt(now);
        return f;
    }

    public static WorkOrder toEntity(WorkOrderDTOIn d, Capture capture, Instant now) {
        WorkOrder w = new WorkOrder();
        w.setWorkOrderId(d.workOrderId() == null || d.workOrderId().isBlank() ? Ids.newId("wo") : d.workOrderId());
        w.setInspectionId(capture.getInspectionId());
        w.setCaptureId(capture.getCaptureId());
        w.setFindingIds(new ArrayList<>(d.findingIds()));
        w.setLocation(d.location());
        w.setTreatmentId(d.treatmentId());
        w.setStepsAr(d.stepsAr());
        w.setStepsEn(d.stepsEn());
        w.setMaterialsAr(d.materialsAr());
        w.setMaterialsEn(d.materialsEn());
        w.setToolsAr(d.toolsAr());
        w.setToolsEn(d.toolsEn());
        w.setFixWaterSourceFirst(d.fixWaterSourceFirst());
        w.setCitation(d.citation());
        w.setEscalation(d.escalation());
        w.setStatus(d.status());
        w.setCreatedAt(d.createdAt() == null ? now : Times.parseOrNow(d.createdAt()));
        return w;
    }
}
