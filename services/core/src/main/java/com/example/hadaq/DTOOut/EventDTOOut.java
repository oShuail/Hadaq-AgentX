package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.EventSource;
import com.example.hadaq.Enum.EventType;
import com.example.hadaq.Enum.Provenance;

import java.time.Instant;
import java.util.Map;

public record EventDTOOut(
        String eventId,
        EventType type,
        String buildingId,
        Instant ts,
        EventSource source,
        Map<String, Object> payload,
        Provenance provenance
) {
}
