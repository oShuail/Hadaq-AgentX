package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// new = waiting for the vision worker
public enum PhotoStatus {
    NEW("new"),
    PROCESSED("processed");

    private final String value;

    PhotoStatus(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static PhotoStatus fromValue(String value) {
        for (PhotoStatus item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown PhotoStatus: " + value);
    }
}
