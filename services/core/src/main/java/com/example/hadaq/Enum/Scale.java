package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// absolute = metres known (scale reference or wall size), relative = proportions only
public enum Scale {
    ABSOLUTE("absolute"),
    RELATIVE("relative");

    private final String value;

    Scale(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static Scale fromValue(String value) {
        for (Scale item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown Scale: " + value);
    }
}
