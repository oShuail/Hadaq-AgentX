package com.example.hadaq.DTOOut;

import com.fasterxml.jackson.annotation.JsonInclude;

// GET /evidence/{id}: the record behind any ID the agent cites. status / request only for simulations
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceDTOOut(
        String evidenceId,
        String kind,
        String status,
        Object request,
        Object value
) {
    public static EvidenceDTOOut of(String evidenceId, String kind, Object value) {
        return new EvidenceDTOOut(evidenceId, kind, null, null, value);
    }
}
