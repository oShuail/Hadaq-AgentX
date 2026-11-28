package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// v2 damage types: the DGDA glossary six, two suspected-only, three bonus (contracts/README.md v2)
public enum DamageType {
    PLASTER_LOSS("plaster_loss"),
    CRACK("crack"),
    BASE_EROSION("base_erosion"),
    RAIN_GROOVES("rain_grooves"),
    SALT_EFFLORESCENCE("salt_efflorescence"),
    VOIDS_BURROWS("voids_burrows"),
    STRUCTURAL_DEFORMATION("structural_deformation"),
    INCOMPATIBLE_REPAIR("incompatible_repair"),
    PARAPET_LOSS("parapet_loss"),
    TIMBER_DECAY("timber_decay"),
    BIOLOGICAL_GROWTH("biological_growth");

    private final String value;

    DamageType(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static DamageType fromValue(String value) {
        for (DamageType item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown DamageType: " + value);
    }
}
