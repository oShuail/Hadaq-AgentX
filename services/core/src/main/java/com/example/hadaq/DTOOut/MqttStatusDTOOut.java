package com.example.hadaq.DTOOut;

import java.time.Instant;

// status: "online" or "offline" as the device published it
public record MqttStatusDTOOut(String status, Instant ts) {
}
