package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// where a treatment comes from
public enum CitationStatus {
    APPROVED_LIBRARY("approved_library"),
    TEAM_PROPOSAL("team_proposal");

    private final String value;

    CitationStatus(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static CitationStatus fromValue(String value) {
        for (CitationStatus item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown CitationStatus: " + value);
    }
}
