package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// computed by the API from the readings
public enum SensorStatus {
    OK("ok"),
    SUSPECT("suspect"),
    FAULT("fault"),
    STALE("stale"),
    NO_DATA("no_data");

    private final String value;

    SensorStatus(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static SensorStatus fromValue(String value) {
        for (SensorStatus item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown SensorStatus: " + value);
    }
}
