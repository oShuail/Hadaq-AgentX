package com.example.hadaq.DTOIn;

import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.ReadingQuality;
import com.example.hadaq.Enum.SensorKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// a reading from the ESP32 node or the sensor simulator (MQTT hadaq/v1/{building}/sensors/{sensor} or POST /readings)
public record SensorReadingDTOIn(
        @NotBlank(message = "sensor_id is required")
        @Size(max = 20, message = "sensor_id is too long")
        String sensorId,

        @NotBlank(message = "building_id is required")
        @Size(max = 10, message = "building_id is too long")
        String buildingId,

        @NotBlank(message = "zone_id is required")
        @Size(max = 30, message = "zone_id is too long")
        String zoneId,

        @NotNull(message = "kind is required")
        SensorKind kind,

        @NotNull(message = "value is required")
        Double value,

        @NotBlank(message = "unit is required")
        @Size(max = 10, message = "unit is too long")
        String unit,

        // ISO 8601, e.g. 2026-11-20T10:15:00+03:00 (no offset = UTC)
        @NotBlank(message = "ts is required")
        String ts,

        @NotNull(message = "provenance is required")
        Provenance provenance,

        @Size(max = 40, message = "device_id is too long")
        String deviceId,

        ReadingQuality quality
) {
    public SensorReadingDTOIn {
        if (quality == null) {
            quality = ReadingQuality.OK;
        }
    }
}
