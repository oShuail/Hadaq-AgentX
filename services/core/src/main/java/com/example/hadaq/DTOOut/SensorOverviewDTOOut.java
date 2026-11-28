package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.SensorKind;
import com.example.hadaq.Enum.SensorStatus;

import java.util.List;

// everything the web app and the agents need about one sensor before they trust its number
public record SensorOverviewDTOOut(
        String sensorId,
        String label,
        SensorKind kind,
        String unit,
        String zoneId,
        String deviceId,
        SensorStatus status,
        List<String> reasons,
        LatestReadingDTOOut latest,
        WindowStatsDTOOut window
) {
}
