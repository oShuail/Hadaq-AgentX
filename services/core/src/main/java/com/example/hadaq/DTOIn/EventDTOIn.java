package com.example.hadaq.DTOIn;

import com.example.hadaq.Enum.EventSource;
import com.example.hadaq.Enum.EventType;
import com.example.hadaq.Enum.Provenance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

// event_id and ts are optional: the API fills them (evt_ + 12 hex, now)
public record EventDTOIn(
        @Pattern(regexp = "^evt_[0-9a-zA-Z]{1,32}$", message = "event_id must look like evt_3f9a1c2b7d4e")
        String eventId,

        @NotNull(message = "type is required")
        EventType type,

        @NotBlank(message = "building_id is required")
        @Size(max = 10, message = "building_id is too long")
        String buildingId,

        String ts,

        @NotNull(message = "source is required")
        EventSource source,

        Map<String, Object> payload,

        @NotNull(message = "provenance is required")
        Provenance provenance
) {
    public EventDTOIn {
        if (payload == null) {
            payload = new LinkedHashMap<>();
        }
    }
}
