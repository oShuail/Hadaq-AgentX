package com.example.hadaq.Mapper;

import com.example.hadaq.DTOIn.DetectionDTOIn;
import com.example.hadaq.DTOIn.EventDTOIn;
import com.example.hadaq.DTOIn.SensorReadingDTOIn;
import com.example.hadaq.DTOOut.DetectionDTOOut;
import com.example.hadaq.DTOOut.EventDTOOut;
import com.example.hadaq.DTOOut.HistoryPointDTOOut;
import com.example.hadaq.DTOOut.LiveReadingDTOOut;
import com.example.hadaq.DTOOut.PhotoDTOOut;
import com.example.hadaq.DTOOut.SensorReadingDTOOut;
import com.example.hadaq.Entity.Detection;
import com.example.hadaq.Entity.Event;
import com.example.hadaq.Entity.Photo;
import com.example.hadaq.Entity.SensorReading;
import com.example.hadaq.Model.HealthResult;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Times;

import java.time.Instant;
import java.util.LinkedHashMap;

public final class DTOMapper {

    private DTOMapper() {
    }

    // ---------------- readings ----------------

    public static SensorReading toEntity(SensorReadingDTOIn dto) {
        SensorReading reading = new SensorReading();
        reading.setSensorId(dto.sensorId());
        reading.setBuildingId(dto.buildingId());
        reading.setZoneId(dto.zoneId());
        reading.setKind(dto.kind());
        reading.setValue(dto.value());
        reading.setUnit(dto.unit());
        reading.setTs(Times.parse(dto.ts()));
        reading.setProvenance(dto.provenance());
        reading.setDeviceId(dto.deviceId());
        reading.setQuality(dto.quality());
        return reading;
    }

    public static SensorReadingDTOOut toDTO(SensorReading r) {
        return new SensorReadingDTOOut(r.getSensorId(), r.getBuildingId(), r.getZoneId(), r.getKind(), r.getValue(),
                r.getUnit(), r.getTs(), r.getProvenance(), r.getDeviceId(), r.getQuality(), r.getEvidenceId());
    }

    public static LiveReadingDTOOut toLiveDTO(SensorReading r, HealthResult health) {
        return new LiveReadingDTOOut(r.getSensorId(), r.getBuildingId(), r.getZoneId(), r.getKind(), r.getValue(),
                r.getUnit(), r.getTs(), r.getProvenance(), r.getDeviceId(), r.getQuality(), r.getEvidenceId(),
                health.status(), health.reasons());
    }

    public static HistoryPointDTOOut toHistoryPoint(SensorReading r) {
        return new HistoryPointDTOOut(r.getTs(), r.getValue(), r.getQuality(), r.getEvidenceId());
    }

    // ---------------- events ----------------

    public static Event toEntity(EventDTOIn dto) {
        Event event = new Event();
        event.setEventId(dto.eventId() == null || dto.eventId().isBlank() ? Ids.newId("evt") : dto.eventId());
        event.setType(dto.type());
        event.setBuildingId(dto.buildingId());
        event.setTs(Times.parseOrNow(dto.ts()));
        event.setSource(dto.source());
        event.setPayload(new LinkedHashMap<>(dto.payload()));
        event.setProvenance(dto.provenance());
        return event;
    }

    public static EventDTOOut toDTO(Event e) {
        return new EventDTOOut(e.getEventId(), e.getType(), e.getBuildingId(), e.getTs(), e.getSource(),
                e.getPayload(), e.getProvenance());
    }

    // ---------------- photos ----------------

    public static PhotoDTOOut toDTO(Photo p) {
        return new PhotoDTOOut(p.getPhotoId(), p.getCameraId(), p.getBuildingId(), p.getCapturedAt(),
                p.getProvenance(), p.getFileUrl(), p.getSequence(), p.getFrame(), p.getStatus());
    }

    // ---------------- detections ----------------

    public static Detection toEntity(DetectionDTOIn dto, Instant createdAt) {
        Detection d = new Detection();
        d.setDetectionId(dto.detectionId() == null || dto.detectionId().isBlank() ? Ids.newId("det") : dto.detectionId());
        d.setPhotoId(dto.photoId());
        d.setCameraId(dto.cameraId());
        d.setBuildingId(dto.buildingId());
        d.setZoneId(dto.zoneId());
        d.setCls(dto.cls());
        d.setConfidence(dto.confidence());
        d.setBboxXyxy(dto.bboxXyxy());
        d.setPolygon(dto.polygon());
        d.setLengthPx(dto.lengthPx());
        d.setAreaPx(dto.areaPx());
        d.setLengthMm(dto.lengthMm());
        d.setAreaCm2(dto.areaCm2());
        d.setModel(dto.model());
        d.setProvenance(dto.provenance());
        d.setChange(dto.change());
        d.setGrowthPct(dto.growthPct());
        d.setCreatedAt(createdAt);
        return d;
    }

    public static DetectionDTOOut toDTO(Detection d) {
        return new DetectionDTOOut(d.getDetectionId(), d.getPhotoId(), d.getCameraId(), d.getBuildingId(),
                d.getZoneId(), d.getCls(), d.getConfidence(), d.getBboxXyxy(), d.getPolygon(), d.getLengthPx(),
                d.getAreaPx(), d.getLengthMm(), d.getAreaCm2(), d.getModel(), d.getProvenance(), d.getChange(),
                d.getGrowthPct());
    }
}
