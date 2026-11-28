package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// what the device says about its own reading
public enum ReadingQuality {
    OK("ok"),
    SUSPECT("suspect"),
    FAULT("fault");

    private final String value;

    ReadingQuality(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static ReadingQuality fromValue(String value) {
        for (ReadingQuality item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown ReadingQuality: " + value);
    }
}
