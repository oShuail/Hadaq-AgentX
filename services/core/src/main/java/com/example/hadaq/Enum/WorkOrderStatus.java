package com.example.hadaq.Enum;

import com.example.hadaq.Api.ApiException;
import com.fasterxml.jackson.annotation.JsonValue;

// a treatment draft; approved / rejected when its inspection is approved
public enum WorkOrderStatus {
    DRAFT_NEEDS_FIELD_CHECK("draft_needs_field_check"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String value;

    WorkOrderStatus(String value) {
        this.value = value;
    }

    // the value on the wire (JSON, MQTT, database)
    @JsonValue
    public String value() {
        return value;
    }

    public static WorkOrderStatus fromValue(String value) {
        for (WorkOrderStatus item : values()) {
            if (item.value.equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new ApiException("Unknown WorkOrderStatus: " + value);
    }
}
