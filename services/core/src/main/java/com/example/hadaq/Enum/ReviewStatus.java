package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// the conservator's decision on one finding
public enum ReviewStatus {
    PENDING("pending"),
    ACCEPTED("accepted"),
    CORRECTED("corrected"),
    REJECTED("rejected");

    private final String value;

    ReviewStatus(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static ReviewStatus fromValue(String value) {
        for (ReviewStatus item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown ReviewStatus: " + value);
    }
}
