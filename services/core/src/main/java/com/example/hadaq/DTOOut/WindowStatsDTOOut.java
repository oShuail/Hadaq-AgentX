package com.example.hadaq.DTOOut;

import java.time.Instant;

// trend over the last N usable readings of one sensor
public record WindowStatsDTOOut(
        int readings,
        int skipped,
        Instant fromTs,
        Instant toTs,
        Double first,
        Double last,
        Double min,
        Double max,
        Double mean,
        Double change,
        Double ratePerMin,
        String trend,
        String firstEvidenceId,
        String lastEvidenceId,
        String maxEvidenceId
) {
}
