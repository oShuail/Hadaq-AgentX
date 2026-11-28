package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// import = picked from the photo library
public enum CaptureSource {
    CAMERA("camera"),
    IMPORT("import");

    private final String value;

    CaptureSource(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static CaptureSource fromValue(String value) {
        for (CaptureSource item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown CaptureSource: " + value);
    }
}
