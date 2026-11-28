package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.CaptureSource;
import com.example.hadaq.Enum.CaptureStatus;
import com.example.hadaq.Enum.DamageType;
import com.example.hadaq.Enum.DetectionChange;
import com.example.hadaq.Enum.Escalation;
import com.example.hadaq.Enum.InspectionStatus;
import com.example.hadaq.Enum.OriginalFabric;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.Scale;
import com.example.hadaq.Enum.WorkOrderStatus;
import com.example.hadaq.Model.V2Values;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/*
 * The v2 responses (contracts/README.md "v2", packages/hadaq_schemas/hadaq_schemas/inspection.py), snake_case on the wire.
 * Grouped in one file because they nest into each other (Inspection holds Captures, Findings and WorkOrders).
 */
public final class V2Out {

    private V2Out() {
    }

    // GET /api/v2/registry
    public record Registry(String siteId, List<Building> buildings) {
    }

    public record Building(String id, String nameAr, String nameEn, double lon, double lat, boolean hasModel, List<Wall> walls) {
    }

    // also sent to the AI worker inside ProcessRequest
    public record Wall(String id, String buildingId, String name, double azimuthDeg, double widthM, double heightM,
                       int rows, int cols, V2Values.BaseLine baseLine, boolean mainFacade, boolean visitorRoute,
                       OriginalFabric originalFabric, Provenance provenance) {
    }

    // GET /api/v2/surveys
    public record Survey(String surveyId, String title, String assignee, Instant due, List<V2Values.SurveyTask> tasks) {
    }

    // GET /api/v2/captures/{id}: the CaptureMeta fields plus what the core API knows
    public record Capture(String clientUuid, String surveyId, String buildingId, String wallId, String segment,
                          Instant capturedAt, String surveyor, String device, CaptureSource source,
                          V2Values.GeoFix gps, Double headingDeg, Double tiltDeg, V2Values.CaptureQuality quality,
                          List<List<Double>> cornersPx, Integer imageWidthPx, Integer imageHeightPx, Integer facesHidden,
                          Provenance provenance,
                          String captureId, CaptureStatus status, String inspectionId, String fileUrl,
                          String rectifiedUrl, Double pxPerM, Scale scale, Integer facesHiddenServer,
                          int findingsCount, boolean urgent, String error, Instant createdAt) {
    }

    // 202 of POST /api/v2/captures (also for a repeated client_uuid, with duplicate = true)
    public record CaptureAccepted(String captureId, CaptureStatus status, boolean duplicate) {
    }

    public record Finding(String findingId, String captureId, String inspectionId, String buildingId, String wallId,
                          String zoneId, DamageType damageType, int severity, double confidence, boolean suspected,
                          List<Double> boxPx, List<Double> boxWallM, List<List<Double>> polygonWallM, Double lengthCm,
                          Double widthMm, Double areaM2, DetectionChange change, String previousFindingId,
                          V2Values.Lookalike lookalike, V2Values.Priority priority, V2Values.Review review,
                          String model, Provenance provenance, List<String> evidenceIds) {
    }

    public record WorkOrder(String workOrderId, String inspectionId, List<String> findingIds,
                            V2Values.WorkOrderLocation location, String treatmentId,
                            List<String> stepsAr, List<String> stepsEn, List<String> materialsAr, List<String> materialsEn,
                            List<String> toolsAr, List<String> toolsEn, boolean fixWaterSourceFirst,
                            V2Values.Citation citation, Escalation escalation, WorkOrderStatus status, Instant createdAt) {
    }

    // GET /api/v2/inspections/{id}
    public record Inspection(String inspectionId, String surveyId, String buildingId, String wallId, Instant startedAt,
                             List<Capture> captures, List<Finding> findings, List<WorkOrder> workOrders,
                             InspectionStatus status, String approvedBy, Instant approvedAt) {
    }

    // GET /api/v2/site/priority: one row per building, worst first
    public record SitePriorityRow(String buildingId, String nameAr, String nameEn, Escalation worstEscalation,
                                  Map<String, Integer> openFindings, String topReasonAr, Instant lastSurveyAt) {
    }

    // core API -> AI worker: POST {AI_URL}/ai/process
    public record ProcessRequest(String captureId, String fileUrl, String callbackUrl, Wall wall,
                                 List<List<Double>> cornersPx, List<Finding> previousFindings) {
    }
}
