package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// who raised an event
public enum EventSource {
    SCENARIO_PANEL("scenario_panel"),
    MONITORING("monitoring"),
    VISION("vision"),
    SIMULATION("simulation"),
    AGENT("agent"),
    API("api"),
    DEVICE("device");

    private final String value;

    EventSource(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static EventSource fromValue(String value) {
        for (EventSource item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown EventSource: " + value);
    }
}
