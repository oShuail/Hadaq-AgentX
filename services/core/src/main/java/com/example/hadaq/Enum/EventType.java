package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// every event type in contracts/README.md
public enum EventType {
    STORM_STARTED("storm_started"),
    STORM_ENDED("storm_ended"),
    NEW_CRACK("new_crack"),
    SENSOR_FAULT("sensor_fault"),
    DEMO_RESET("demo_reset"),
    MOISTURE_ANOMALY("moisture_anomaly"),
    NEW_PHOTO("new_photo"),
    DETECTION_READY("detection_ready"),
    SIMULATION_DONE("simulation_done"),
    RECOMMENDATION_READY("recommendation_ready"),
    AGENT_NOTE("agent_note");

    private final String value;

    EventType(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static EventType fromValue(String value) {
        for (EventType item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown EventType: " + value);
    }
}
