package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// replay = a real past storm (Open-Meteo ERA5), synthetic_storm = generated
public enum WeatherMode {
    REPLAY("replay"),
    SYNTHETIC_STORM("synthetic_storm");

    private final String value;

    WeatherMode(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static WeatherMode fromValue(String value) {
        for (WeatherMode item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown WeatherMode: " + value);
    }
}
