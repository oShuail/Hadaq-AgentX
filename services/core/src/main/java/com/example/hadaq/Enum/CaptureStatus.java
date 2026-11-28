package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// queued is the phone's own state; the core API uses the others
public enum CaptureStatus {
    QUEUED("queued"),
    UPLOADED("uploaded"),
    PROCESSING("processing"),
    ANALYZED("analyzed"),
    FAILED("failed");

    private final String value;

    CaptureStatus(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static CaptureStatus fromValue(String value) {
        for (CaptureStatus item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown CaptureStatus: " + value);
    }
}
