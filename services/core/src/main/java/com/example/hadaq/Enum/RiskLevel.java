package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// ABC magnitude bands (ours, ASSUMED)
public enum RiskLevel {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    VERY_HIGH("very_high");

    private final String value;

    RiskLevel(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static RiskLevel fromValue(String value) {
        for (RiskLevel item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown RiskLevel: " + value);
    }
}
