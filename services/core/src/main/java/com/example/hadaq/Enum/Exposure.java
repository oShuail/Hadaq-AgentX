package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// the app's exposure check
public enum Exposure {
    OK("ok"),
    DARK("dark"),
    BRIGHT("bright");

    private final String value;

    Exposure(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static Exposure fromValue(String value) {
        for (Exposure item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown Exposure: " + value);
    }
}
