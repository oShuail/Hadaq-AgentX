package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// how much of a wall is original
public enum OriginalFabric {
    ORIGINAL("original"),
    RESTORED("restored"),
    MIXED("mixed"),
    UNKNOWN("unknown");

    private final String value;

    OriginalFabric(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static OriginalFabric fromValue(String value) {
        for (OriginalFabric item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown OriginalFabric: " + value);
    }
}
