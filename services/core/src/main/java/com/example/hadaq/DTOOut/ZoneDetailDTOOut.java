package com.example.hadaq.DTOOut;

import java.util.List;
import java.util.Map;

// GET /buildings/{b}/zones/{zone}: everything known about one wall zone
public record ZoneDetailDTOOut(
        String zoneId,
        String wallId,
        int row,
        int col,
        String position,                 // base, middle, top
        List<SensorOverviewDTOOut> sensors,
        List<DetectionDTOOut> detections,
        RiskScoreDTOOut risk,
        Map<String, Object> simulation   // {"run_id", "peak_moisture", "hours_above_threshold", "erosion_index"} or null
) {
}
