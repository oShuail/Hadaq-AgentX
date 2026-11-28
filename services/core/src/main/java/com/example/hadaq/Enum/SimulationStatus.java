package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// a simulation run
public enum SimulationStatus {
    RUNNING("running"),
    DONE("done"),
    FAILED("failed");

    private final String value;

    SimulationStatus(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static SimulationStatus fromValue(String value) {
        for (SimulationStatus item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown SimulationStatus: " + value);
    }
}
