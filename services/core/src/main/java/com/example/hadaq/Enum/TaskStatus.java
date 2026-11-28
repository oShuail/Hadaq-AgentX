package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// one wall to photograph in a survey
public enum TaskStatus {
    NOT_STARTED("not_started"),
    PARTIAL("partial"),
    DONE("done"),
    SYNCED("synced");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static TaskStatus fromValue(String value) {
        for (TaskStatus item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown TaskStatus: " + value);
    }
}
