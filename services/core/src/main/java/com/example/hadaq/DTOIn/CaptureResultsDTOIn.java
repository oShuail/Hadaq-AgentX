package com.example.hadaq.DTOIn;

import com.example.hadaq.Enum.Scale;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

// AI worker -> core API: the `results` part of POST /api/v2/captures/{id}/results (contracts v2 CaptureResults)
public record CaptureResultsDTOIn(
        @NotBlank(message = "capture_id is required")
        String captureId,

        Boolean ok,
        String error,
        Scale scale,
        Double pxPerM,

        @Min(value = 0, message = "faces_hidden_server cannot be negative")
        Integer facesHiddenServer,

        List<@Valid FindingDTOIn> findings,
        List<@Valid WorkOrderDTOIn> workOrders,
        String summaryAr,
        String summaryEn,

        @NotBlank(message = "detector is required")
        String detector,

        String chain
) {
    public CaptureResultsDTOIn {
        if (ok == null) {
            ok = true;
        }
        if (scale == null) {
            scale = Scale.RELATIVE;
        }
        if (facesHiddenServer == null) {
            facesHiddenServer = 0;
        }
        findings = findings == null ? new ArrayList<>() : findings;
        workOrders = workOrders == null ? new ArrayList<>() : workOrders;
        if (chain == null) {
            chain = "hadaq_agents.chain@v1";
        }
    }
}
