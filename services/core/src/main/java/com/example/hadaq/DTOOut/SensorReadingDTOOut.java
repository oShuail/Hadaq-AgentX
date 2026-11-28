package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.ReadingQuality;
import com.example.hadaq.Enum.SensorKind;

import java.time.Instant;

public record SensorReadingDTOOut(
        String sensorId,
        String buildingId,
        String zoneId,
        SensorKind kind,
        Double value,
        String unit,
        Instant ts,
        Provenance provenance,
        String deviceId,
        ReadingQuality quality,
        String evidenceId
) {
}
