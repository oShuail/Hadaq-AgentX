package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// the detector classes (vision, RF-DETR-Seg)
public enum DefectClass {
    CRACK("crack"),
    EROSION("erosion"),
    PLASTER_LOSS("plaster_loss"),
    MOISTURE_STAIN("moisture_stain"),
    MISSING_MATERIAL("missing_material");

    private final String value;

    DefectClass(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static DefectClass fromValue(String value) {
        for (DefectClass item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown DefectClass: " + value);
    }
}
