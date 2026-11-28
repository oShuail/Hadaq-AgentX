package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.ReadingQuality;

import java.time.Instant;

public record HistoryPointDTOOut(
        Instant ts,
        Double value,
        ReadingQuality quality,
        String evidenceId
) {
}
