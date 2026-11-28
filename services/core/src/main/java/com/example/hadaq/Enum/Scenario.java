package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// what-if scenarios of the moisture model
public enum Scenario {
    BASELINE("baseline"),
    STORM_REPLAY("storm_replay"),
    RAIN_X2("rain_x2"),
    DRAINAGE_FIXED("drainage_fixed");

    private final String value;

    Scenario(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static Scenario fromValue(String value) {
        for (Scenario item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown Scenario: " + value);
    }
}
