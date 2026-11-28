package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// urgent عاجل, high مرتفع, medium متوسط, low منخفض (declared in that order: ordinal() = rank)
public enum Escalation {
    URGENT("urgent"),
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    private final String value;

    Escalation(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static Escalation fromValue(String value) {
        for (Escalation item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown Escalation: " + value);
    }
}
