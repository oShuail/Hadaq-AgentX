package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.ReadingQuality;

import java.time.Instant;

public record LatestReadingDTOOut(
        Double value,
        Instant ts,
        Double ageS,
        ReadingQuality quality,
        Provenance provenance,
        String evidenceId
) {
}
