package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.DefectClass;
import com.example.hadaq.Enum.DetectionChange;
import com.example.hadaq.Enum.Provenance;

import java.util.List;

public record DetectionDTOOut(
        String detectionId,
        String photoId,
        String cameraId,
        String buildingId,
        String zoneId,
        DefectClass cls,
        Double confidence,
        List<Double> bboxXyxy,
        List<List<Double>> polygon,
        Double lengthPx,
        Double areaPx,
        Double lengthMm,
        Double areaCm2,
        String model,
        Provenance provenance,
        DetectionChange change,
        Double growthPct
) {
}
