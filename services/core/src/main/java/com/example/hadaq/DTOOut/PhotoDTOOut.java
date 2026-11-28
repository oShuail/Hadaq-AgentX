package com.example.hadaq.DTOOut;

import com.example.hadaq.Enum.PhotoStatus;
import com.example.hadaq.Enum.Provenance;

import java.time.Instant;

public record PhotoDTOOut(
        String photoId,
        String cameraId,
        String buildingId,
        Instant capturedAt,
        Provenance provenance,
        String fileUrl,
        String sequence,
        Integer frame,
        PhotoStatus status
) {
}
