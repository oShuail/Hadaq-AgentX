package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// one wall in one survey
public enum InspectionStatus {
    OPEN("open"),
    APPROVED("approved");

    private final String value;

    InspectionStatus(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static InspectionStatus fromValue(String value) {
        for (InspectionStatus item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown InspectionStatus: " + value);
    }
}
