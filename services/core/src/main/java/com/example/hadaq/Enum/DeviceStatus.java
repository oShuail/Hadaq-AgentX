package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// a sensor node (real or simulated)
public enum DeviceStatus {
    ONLINE("online"),
    OFFLINE("offline"),
    NEVER_SEEN("never_seen");

    private final String value;

    DeviceStatus(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static DeviceStatus fromValue(String value) {
        for (DeviceStatus item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown DeviceStatus: " + value);
    }
}
