package com.example.hadaq.Util;

import com.example.hadaq.Api.ApiException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public final class Times {

    private Times() {
    }

    // microseconds, like Python and MySQL DATETIME(6)
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    // ISO 8601 with an offset (2026-11-20T10:15:00+03:00, ...Z); a time without an offset is read as UTC
    public static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(text).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException ignored) {
            // no offset
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException exception) {
            throw new ApiException("Invalid time '" + value + "' (expected ISO 8601, e.g. 2026-11-20T10:15:00+03:00)");
        }
    }

    public static Instant parseOrNow(String value) {
        Instant parsed = parse(value);
        return parsed == null ? now() : parsed;
    }
}
