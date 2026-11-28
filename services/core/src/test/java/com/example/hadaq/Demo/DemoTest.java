package com.example.hadaq.Demo;

import com.example.hadaq.DTOOut.DetectionDTOOut;
import com.example.hadaq.Enum.DefectClass;
import com.example.hadaq.Enum.DetectionChange;
import com.example.hadaq.Enum.ReadingQuality;
import com.example.hadaq.Enum.SensorKind;
import com.example.hadaq.Model.SensorSpec;
import com.example.hadaq.Model.WallSpec;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoTest {

    private static final SensorSpec BASE = new SensorSpec("B01-S04", SensorKind.MOISTURE, "%rel", "B01-W01-R1C6", "sim-01", "sim", "Base");
    private static final SensorSpec UPPER = new SensorSpec("B01-S05", SensorKind.MOISTURE, "%rel", "B01-W01-R3C4", "sim-01", "sim", "Upper");
    private static final SensorSpec CRACK = new SensorSpec("B01-S06", SensorKind.CRACK_WIDTH, "mm", "B01-W01-R2C5", "sim-01", "sim", "Crack");
    private static final double[] BBOX = {80, 80, 1520, 1120};
    private static final WallSpec WALL = new WallSpec("W01", "B01", 270, 4, 8);

    // Saleh's two-minute run: a 25 mm/h storm for 35 s at 720x (2 s steps) takes the base past the 60 %rel line
    @Test
    void stormWetsTheBaseMostAndItStaysWet() {
        WallModel world = new WallModel(List.of(BASE, UPPER, CRACK), Instant.parse("2026-11-27T09:00:00Z"), 42L);
        double start = world.moistureOf("B01-S04");
        world.onEvent("storm_started", Map.of("peak_rain_mm_h", 25), 0);
        for (int i = 0; i < 18; i++) {
            world.advance(2 * 720 / 3600.0);
        }
        double baseWet = world.moistureOf("B01-S04");
        double upperWet = world.moistureOf("B01-S05");
        assertTrue(baseWet > 60, "base " + baseWet);
        assertTrue(baseWet > upperWet, "the base gets the most water");

        world.onEvent("storm_ended", Map.of(), 0);
        for (int i = 0; i < 10; i++) {
            world.advance(2 * 720 / 3600.0);
        }
        assertTrue(world.moistureOf("B01-S04") > start + 20, "ponding keeps the base wet after the rain");

        world.onEvent("demo_reset", Map.of(), 0);
        assertEquals(start, world.moistureOf("B01-S04"), 1e-9);
    }

    @Test
    void faultsAndNewCrack() {
        WallModel world = new WallModel(List.of(BASE, UPPER, CRACK), Instant.parse("2026-11-27T09:00:00Z"), 1L);
        world.onEvent("sensor_fault", Map.of("sensor_id", "B01-S04", "mode", "spike", "duration_s", 45), 100);
        WallModel.Reading spike = world.read(BASE, 110);
        assertEquals(ReadingQuality.FAULT, spike.quality());
        assertTrue(Set.of(3.0, 96.0, 8.0, 91.0).contains(spike.value()));
        assertEquals(ReadingQuality.OK, world.read(BASE, 200).quality(), "the fault clears after 45 s");

        world.onEvent("sensor_fault", Map.of("sensor_id", "B01-S05", "mode", "dropout"), 0);
        assertNull(world.read(UPPER, 1).value());

        double before = world.read(CRACK, 0).value();
        world.onEvent("new_crack", Map.of("mm", 0.35), 0);
        assertTrue(world.read(CRACK, 0).value() > before + 0.3);
    }

    @Test
    void mockDetectorMapsTheDemoDamageToTheRightZones() {
        Set<String> found = MockVisionWorker.detect(BBOX, false, 1).stream()
                .map(r -> MockVisionWorker.pixelToZone((r.box()[0] + r.box()[2]) / 2, (r.box()[1] + r.box()[3]) / 2, BBOX, "B01", WALL)
                        + " " + r.cls().value())
                .collect(Collectors.toSet());
        assertEquals(Set.of("B01-W01-R2C5 crack", "B01-W01-R3C2 missing_material", "B01-W01-R3C6 erosion",
                "B01-W01-R1C7 moisture_stain", "B01-W01-R4C7 missing_material", "B01-W01-R1C4 erosion"), found);
        // damage frame 2 adds the plaster loss
        assertTrue(MockVisionWorker.detect(BBOX, true, 2).stream().anyMatch(r -> r.cls() == DefectClass.PLASTER_LOSS));
    }

    @Test
    void growingCrackIsLabelledGrown() {
        var first = MockVisionWorker.detect(BBOX, true, 1).get(0);   // the crack
        var second = MockVisionWorker.detect(BBOX, true, 2).get(0);  // 15% longer
        DetectionDTOOut previous = new DetectionDTOOut("det_a", "ph_a", "B01-CAM01", "B01", "B01-W01-R2C5", DefectClass.CRACK,
                0.83, null, null, first.lengthPx(), first.areaPx(), null, null, "mock-detector", null, null, null);
        List<Map<String, Object>> current = new ArrayList<>();
        Map<String, Object> crack = new HashMap<>();
        crack.put("zone", "B01-W01-R2C5");
        crack.put("raw", second);
        current.add(crack);
        Map<String, Object> other = new HashMap<>();
        other.put("zone", "B01-W01-R3C2");
        other.put("raw", MockVisionWorker.detect(BBOX, true, 2).get(1));
        current.add(other);

        MockVisionWorker.labelChanges(current, List.of(previous));
        assertEquals(DetectionChange.GROWN, crack.get("change"));
        assertEquals(15.0, (double) crack.get("growth"), 1e-9);
        assertEquals(DetectionChange.NEW, other.get("change"));
    }

    @Test
    void framesAreJpegsOfTheWall() throws Exception {
        DemoFrames frames = new DemoFrames();
        BufferedImage baseline = ImageIO.read(new ByteArrayInputStream(frames.baseline(1)));
        assertEquals(DemoFrames.WIDTH, baseline.getWidth());
        assertEquals(DemoFrames.HEIGHT, baseline.getHeight());
        assertTrue(frames.damage(8).length > 0);
    }
}
