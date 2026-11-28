package com.example.hadaq.Service;

import com.example.hadaq.DTOOut.HistoryPointDTOOut;
import com.example.hadaq.DTOOut.WindowStatsDTOOut;
import com.example.hadaq.Entity.SensorReading;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.ReadingQuality;
import com.example.hadaq.Enum.SensorKind;
import com.example.hadaq.Enum.SensorStatus;
import com.example.hadaq.Model.HealthResult;
import com.example.hadaq.Model.SensorSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensorHealthServiceTest {

    private static final SensorSpec S04 = new SensorSpec("B01-S04", SensorKind.MOISTURE, "%rel", "B01-W01-R1C6", "sim-01", "sim", "Base moisture");
    private final SensorHealthService health = new SensorHealthService(30);
    private final Instant now = Instant.parse("2026-11-27T12:00:00Z");
    private long nextId = 1;

    // newest first, one reading every 2 s ending at `now`
    private List<SensorReading> series(String sensorId, double... values) {
        List<SensorReading> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            SensorReading r = new SensorReading();
            r.setId(nextId++);
            r.setSensorId(sensorId);
            r.setBuildingId("B01");
            r.setZoneId("B01-W01-R1C6");
            r.setKind(SensorKind.MOISTURE);
            r.setUnit("%rel");
            r.setValue(values[values.length - 1 - i]);
            r.setTs(now.minusSeconds(2L * i));
            r.setProvenance(Provenance.SYNTHETIC);
            r.setQuality(ReadingQuality.OK);
            out.add(r);
        }
        return out;
    }

    @Test
    void noReadingIsNoData() {
        assertEquals(SensorStatus.NO_DATA, health.health(S04, List.of(), now, null).status());
    }

    @Test
    void deviceFaultWins() {
        List<SensorReading> history = series("B01-S04", 20, 21);
        history.get(0).setQuality(ReadingQuality.FAULT);
        HealthResult result = health.health(S04, history, now, null);
        assertEquals(SensorStatus.FAULT, result.status());
        assertEquals(List.of("the device reported a fault"), result.reasons());
    }

    @Test
    void implausibleValueIsSuspect() {
        HealthResult result = health.health(S04, series("B01-S04", 20, 120), now, null);
        assertEquals(SensorStatus.SUSPECT, result.status());
        assertEquals("value 120.0 %rel is outside the plausible range 0 to 100", result.reasons().get(0));
    }

    @Test
    void stuckOnlyWhenAPeerIsMoving() {
        double[] flat = new double[12];
        java.util.Arrays.fill(flat, 18.2);
        List<SensorReading> stuck = series("B01-S04", flat);
        List<SensorReading> movingPeer = series("B01-S07", 20, 22, 25, 28, 31, 34, 37, 40, 43, 46, 49, 52);
        List<SensorReading> calmPeer = series("B01-S07", 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20, 20);

        assertEquals(SensorStatus.SUSPECT, health.health(S04, stuck, now, List.of(movingPeer)).status());
        assertEquals(SensorStatus.OK, health.health(S04, stuck, now, List.of(calmPeer)).status());
    }

    @Test
    void oldReadingIsStale() {
        HealthResult result = health.health(S04, series("B01-S04", 20, 21), now.plusSeconds(95), null);
        assertEquals(SensorStatus.STALE, result.status());
        assertEquals("no reading for 95 s", result.reasons().get(0));
    }

    @Test
    void windowTrendSkipsFaultyReadings() {
        List<SensorReading> history = series("B01-S04", 20, 22, 24, 999, 30);
        history.get(1).setQuality(ReadingQuality.FAULT);  // the 999 one
        WindowStatsDTOOut stats = health.windowStats(S04, history);
        assertEquals(4, stats.readings());
        assertEquals(1, stats.skipped());
        assertEquals("rising", stats.trend());
        assertEquals(10.0, stats.change());
        assertEquals(30.0, stats.max());
        assertTrue(stats.ratePerMin() > 0);
        assertNull(health.windowStats(S04, List.of()));
    }

    @Test
    void downsampleKeepsOldestAndNewest() {
        List<SensorReading> history = series("B01-S04", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        List<HistoryPointDTOOut> points = SensorHealthService.downsample(history, 4);
        assertEquals(4, points.size());
        assertEquals(1.0, points.get(0).value());
        assertEquals(10.0, points.get(3).value());
    }
}
