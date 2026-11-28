package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// what a sensor measures
public enum SensorKind {
    MOISTURE("moisture"),
    TEMPERATURE("temperature"),
    HUMIDITY("humidity"),
    CRACK_WIDTH("crack_width"),
    TILT("tilt");

    private final String value;

    SensorKind(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static SensorKind fromValue(String value) {
        for (SensorKind item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown SensorKind: " + value);
    }
}
