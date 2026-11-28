package com.example.hadaq.Util;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.Model.ZoneRef;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// zone ID: {building}-{wall}-R{row}C{col}, e.g. B01-W01-R1C3. R1 = the base row, C1 = the left column
public final class ZoneIds {

    private static final Pattern ZONE = Pattern.compile("^(B\\d{2})-(W\\d{2})-R(\\d+)C(\\d+)$");

    private ZoneIds() {
    }

    public static String zoneId(String buildingId, String wallId, int row, int col) {
        return buildingId + "-" + wallId + "-R" + row + "C" + col;
    }

    public static ZoneRef parse(String zoneId) {
        Matcher m = zoneId == null ? null : ZONE.matcher(zoneId);
        if (m == null || !m.matches()) {
            throw ApiException.notFound("Bad zone id: '" + zoneId + "' (expected like B01-W01-R1C3)");
        }
        return new ZoneRef(m.group(1), m.group(2), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
    }
}
