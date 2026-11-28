package com.example.hadaq.DTOIn;

import com.example.hadaq.Enum.Escalation;
import com.example.hadaq.Enum.WorkOrderStatus;
import com.example.hadaq.Model.V2Values;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

// a treatment draft from the AI worker (contracts v2 WorkOrderDraft)
public record WorkOrderDTOIn(
        @Size(max = 40, message = "work_order_id is too long")
        String workOrderId,

        String inspectionId,

        @NotEmpty(message = "work order finding_ids must name at least one finding")
        List<String> findingIds,

        @NotNull(message = "work order location is required")
        @Valid
        V2Values.WorkOrderLocation location,

        // id in services/agents/hadaq_agents/treatment_library.json
        @NotBlank(message = "work order treatment_id is required")
        String treatmentId,

        @NotEmpty(message = "work order steps_ar is required")
        List<String> stepsAr,

        List<String> stepsEn,
        List<String> materialsAr,
        List<String> materialsEn,
        List<String> toolsAr,
        List<String> toolsEn,
        Boolean fixWaterSourceFirst,

        @NotNull(message = "work order citation is required")
        @Valid
        V2Values.Citation citation,

        @NotNull(message = "work order escalation is required")
        Escalation escalation,

        WorkOrderStatus status,
        String createdAt
) {
    public WorkOrderDTOIn {
        stepsEn = stepsEn == null ? new ArrayList<>() : stepsEn;
        materialsAr = materialsAr == null ? new ArrayList<>() : materialsAr;
        materialsEn = materialsEn == null ? new ArrayList<>() : materialsEn;
        toolsAr = toolsAr == null ? new ArrayList<>() : toolsAr;
        toolsEn = toolsEn == null ? new ArrayList<>() : toolsEn;
        if (fixWaterSourceFirst == null) {
            fixWaterSourceFirst = false;
        }
        if (status == null) {
            status = WorkOrderStatus.DRAFT_NEEDS_FIELD_CHECK;
        }
    }
}
