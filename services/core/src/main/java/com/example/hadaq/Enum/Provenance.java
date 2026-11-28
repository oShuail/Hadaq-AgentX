package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// where a value comes from (contracts/README.md, Provenance labels)
public enum Provenance {
    MEASURED("measured"),
    DERIVED("derived"),
    MODEL_OUTPUT("model_output"),
    ASSUMED("assumed"),
    SYNTHETIC("synthetic");

    private final String value;

    Provenance(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static Provenance fromValue(String value) {
        for (Provenance item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown Provenance: " + value);
    }
}
