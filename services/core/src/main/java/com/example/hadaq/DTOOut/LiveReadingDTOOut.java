package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.ReadingQuality;
import com.example.hadaq.Enum.SensorKind;
import com.example.hadaq.Enum.SensorStatus;

import java.time.Instant;
import java.util.List;

// the "reading" channel on /ws/live: the reading plus its evidence ID and the sensor's health
public record LiveReadingDTOOut(
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
        String evidenceId,
        SensorStatus status,
        List<String> reasons
) {
}
