package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// the X-Hadaq-User header (hackathon only, no real auth)
public enum Role {
    SURVEYOR("surveyor"),
    CONSERVATOR("conservator"),
    SITE_MANAGER("site_manager");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static Role fromValue(String value) {
        for (Role item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown Role: " + value);
    }
}
