package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.SensorKind;
import com.example.hadaq.Enum.SensorStatus;

import java.util.List;

// GET /buildings/{b}/sensors/{s}: the overview plus the history, oldest first
public record SensorDetailDTOOut(
        String sensorId,
        String label,
        SensorKind kind,
        String unit,
        String zoneId,
        String deviceId,
        SensorStatus status,
        List<String> reasons,
        LatestReadingDTOOut latest,
        WindowStatsDTOOut window,
        List<HistoryPointDTOOut> history
) {
    public static SensorDetailDTOOut of(SensorOverviewDTOOut o, List<HistoryPointDTOOut> history) {
        return new SensorDetailDTOOut(o.sensorId(), o.label(), o.kind(), o.unit(), o.zoneId(), o.deviceId(),
                o.status(), o.reasons(), o.latest(), o.window(), history);
    }
}
