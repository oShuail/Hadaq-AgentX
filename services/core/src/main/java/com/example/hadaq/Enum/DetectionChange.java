package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// compared with the previous photo of the same camera
public enum DetectionChange {
    NEW("new"),
    GROWN("grown"),
    UNCHANGED("unchanged"),
    REPAIRED("repaired");

    private final String value;

    DetectionChange(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static DetectionChange fromValue(String value) {
        for (DetectionChange item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown DetectionChange: " + value);
    }
}
