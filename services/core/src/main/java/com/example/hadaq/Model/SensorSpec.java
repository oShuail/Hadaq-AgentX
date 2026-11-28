package com.example.hadaq.Model;

import com.example.hadaq.Enum.SensorKind;

// one sensor in contracts/building_b01.json
public record SensorSpec(
        String id,
        SensorKind kind,
        String unit,
        String zoneId,
        String deviceId,
        String source,
        String label
) {
}
