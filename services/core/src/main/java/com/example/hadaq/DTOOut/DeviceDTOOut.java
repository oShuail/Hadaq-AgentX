package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.DeviceStatus;

import java.time.Instant;
import java.util.List;

public record DeviceDTOOut(
        String deviceId,
        DeviceStatus status,
        List<String> sensors,
        Instant lastSeen,
        Double lastSeenAgeS,
        MqttStatusDTOOut mqtt,      // the retained MQTT status / last will, null if never seen on MQTT
        List<String> provenance
) {
}
