package com.example.hadaq.Demo;

import com.example.hadaq.Enum.ReadingQuality;
import com.example.hadaq.Enum.SensorKind;
import com.example.hadaq.Model.SensorSpec;
import com.example.hadaq.Util.Numbers;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/*
 * The wall the demo sensors measure (port of devices/sensor-sim/wall_model.py, owner: Saleh).
 *
 * - Rain wets the wall. The base row (R1) gets the most water, the middle rows the least, the parapet row (R4) a
 *   little more again. Water ponding at the wall foot keeps feeding the base for hours after the rain stops.
 * - Drying depends on temperature and humidity; the base dries about three times slower than the upper wall.
 * - Temperature and humidity follow a Riyadh November day and drop / rise during rain.
 * - The crack gauge widens slowly while the wall is wet; the inclinometer creeps once the base is soaked.
 * Deliberately NOT the bucket_v0 simulation maths. Every value is synthetic and ASSUMED.
 * Runs on a simulated clock: advance(dtHours) moves the world forward (720x = one day every two minutes).
 */
public class WallModel {

    private static final ZoneOffset RIYADH = ZoneOffset.ofHours(3);
    private static final Map<Integer, Double> ROW_WETTING = Map.of(1, 1.0, 2, 0.55, 3, 0.4, 4, 0.5);
    private static final double K_RAIN = 0.5;   // %rel per hour per mm/h of rain, at a dry wall
    private static final double K_POND = 0.20;  // %rel per hour per mm of ponded water (base row only)
    private static final Map<Integer, Double> K_DRY = Map.of(1, 0.025, 2, 0.06, 3, 0.07, 4, 0.08);
    private static final double M_SAT = 95.0;
    private static final double POND_MAX = 15.0;
    private static final double POND_DRAIN = 1.0;
    private static final double CRACK_MAX = 3.0;  // mm, the gauge's range (Saleh)
    private static final double[] SPIKE_VALUES = {3.0, 96.0, 8.0, 91.0};

    public record Reading(Double value, ReadingQuality quality) {
    }

    private static final class Fault {
        final String mode;        // spike: garbage values with quality fault; stuck: repeats one value; dropout: sends nothing
        final double untilReal;   // real seconds (System.nanoTime clock) when it clears
        Double stuckValue;

        Fault(String mode, double untilReal) {
            this.mode = mode;
            this.untilReal = untilReal;
        }
    }

    private final List<SensorSpec> sensors;
    private final Instant start;
    private final Random rng;
    private final Map<String, Double> exposure = new HashMap<>();

    private Instant simTime;
    private boolean storm;
    private Double stormLeftH;
    private double peakRain;
    private double rain;
    private double pond;
    private double crackMm;
    private double tiltDeg;
    private final Map<String, Fault> faults = new HashMap<>();
    private final Map<String, Double> moisture = new HashMap<>();

    public WallModel(List<SensorSpec> sensors, Instant start, Long seed) {
        this.sensors = sensors;
        this.start = start;
        this.rng = seed == null ? new Random() : new Random(seed);
        // each sensor sits in a slightly different spot: a fixed +-15% exposure factor per sensor
        for (SensorSpec s : sensors) {
            Random r = new Random((seed + "-" + s.id()).hashCode());
            exposure.put(s.id(), 0.85 + r.nextDouble() * 0.30);
        }
        reset();
    }

    public synchronized void reset() {
        simTime = start;
        storm = false;
        stormLeftH = null;
        peakRain = 12.0;
        rain = 0.0;
        pond = 0.0;
        crackMm = 1.20;
        tiltDeg = 0.12;
        faults.clear();
        moisture.clear();
        double rh = ambient()[1];
        for (SensorSpec s : sensors) {
            if (s.kind() == SensorKind.MOISTURE) {
                moisture.put(s.id(), equilibrium(rh) * exposure.get(s.id()));
            }
        }
    }

    // react to a scenario event (the same events the scenario panel sends)
    public synchronized void onEvent(String type, Map<String, Object> payload, double nowReal) {
        Map<String, Object> p = payload == null ? Map.of() : payload;
        switch (type) {
            case "storm_started" -> {
                storm = true;
                peakRain = Math.min(Math.max(number(p.get("peak_rain_mm_h"), 12.0), 2.0), 60.0);
                Object hours = p.get("hours");
                stormLeftH = hours instanceof Number n && n.doubleValue() != 0 ? n.doubleValue() : null;
            }
            case "storm_ended" -> {
                storm = false;
                stormLeftH = null;
            }
            case "sensor_fault" -> {
                String sensorId = p.get("sensor_id") instanceof String s ? s : "B01-S04";
                String mode = p.get("mode") instanceof String m && List.of("spike", "stuck", "dropout").contains(m) ? m : "spike";
                faults.put(sensorId, new Fault(mode, nowReal + number(p.get("duration_s"), 45.0)));
            }
            case "new_crack" -> crackMm = Math.min(CRACK_MAX, crackMm + number(p.get("mm"), 0.35));
            case "demo_reset" -> reset();
            default -> {
                // not a scenario event
            }
        }
    }

    // ---------- physics ----------

    private double localHour() {
        ZonedDateTime t = simTime.atZone(RIYADH);
        return t.getHour() + t.getMinute() / 60.0;
    }

    // air temperature (degC) and relative humidity (%RH) next to the wall
    private double[] ambient() {
        double day = Math.sin(2 * Math.PI * (localHour() - 9) / 24);  // peaks mid-afternoon
        double temp = 22.0 + 7.0 * day;
        double rh = 32.0 - 14.0 * day;
        if (rain > 0.2) {
            double wet = Math.min(rain / Math.max(peakRain, 1.0), 1.0);
            temp -= 5.0 * wet;
            rh += (92.0 - rh) * wet;
        }
        return new double[]{temp, Math.min(Math.max(rh, 5.0), 100.0)};
    }

    // moisture a dry-season wall settles at for a given air humidity
    private static double equilibrium(double rh) {
        return 10.0 + 0.08 * rh;
    }

    public synchronized void advance(double dtHours) {
        simTime = simTime.plus(Duration.ofNanos((long) (dtHours * 3.6e12)));
        if (storm && stormLeftH != null) {
            stormLeftH -= dtHours;
            if (stormLeftH <= 0) {
                storm = false;
                stormLeftH = null;
            }
        }
        double target = storm ? peakRain : 0.0;
        rain += (target - rain) * Math.min(1.0, dtHours / 1.5);  // builds up and eases off over about 1.5 h
        if (rain < 0.05) {
            rain = 0.0;
        }
        pond = Math.min(POND_MAX, Math.max(0.0, pond + (0.6 * rain - (rain > 0.5 ? 0.0 : POND_DRAIN)) * dtHours));

        double[] air = ambient();
        double temp = air[0];
        double rh = air[1];
        double evaporation = Math.min(Math.max((temp - 5.0) / 25.0, 0.2), 1.5) * (1.0 - rh / 110.0);
        double eq = equilibrium(rh);
        for (SensorSpec s : sensors) {
            if (s.kind() != SensorKind.MOISTURE) {
                continue;
            }
            int row = row(s.zoneId());
            double m = moisture.get(s.id());
            double room = Math.max(0.0, 1.0 - m / M_SAT);
            double wetting = K_RAIN * rain * ROW_WETTING.getOrDefault(row, 0.5) * exposure.get(s.id()) * room;
            if (row == 1) {
                wetting += K_POND * pond * room;
            }
            double drying = K_DRY.getOrDefault(row, 0.07) * evaporation * Math.max(0.0, m - eq);
            moisture.put(s.id(), Math.min(M_SAT, Math.max(0.0, m + (wetting - drying) * dtHours)));
        }

        double wetMid = sensors.stream().filter(s -> s.kind() == SensorKind.MOISTURE && row(s.zoneId()) == 2)
                .mapToDouble(s -> moisture.get(s.id())).max().orElse(0);
        boolean anyMid = sensors.stream().anyMatch(s -> s.kind() == SensorKind.MOISTURE && row(s.zoneId()) == 2);
        crackMm = Math.min(CRACK_MAX, crackMm + 0.0015 * dtHours * (anyMid && wetMid > 25 ? 1.0 : 0.15));
        double baseMean = sensors.stream().filter(s -> s.kind() == SensorKind.MOISTURE && row(s.zoneId()) == 1)
                .mapToDouble(s -> moisture.get(s.id())).average().orElse(0);
        if (baseMean > 50) {
            tiltDeg += 0.0008 * dtHours;
        }
    }

    // ---------- what each sensor reports ----------

    // value null = the sensor sends nothing (dropout)
    public synchronized Reading read(SensorSpec sensor, double nowReal) {
        Fault fault = faults.get(sensor.id());
        if (fault != null && nowReal >= fault.untilReal) {
            faults.remove(sensor.id());
            fault = null;
        }
        if (fault != null && fault.mode.equals("dropout")) {
            return new Reading(null, ReadingQuality.FAULT);
        }
        if (fault != null && fault.mode.equals("spike")) {
            // a broken probe jumping between near-rail values; it says "fault", so the API does not trust it
            return new Reading(SPIKE_VALUES[rng.nextInt(SPIKE_VALUES.length)], ReadingQuality.FAULT);
        }
        double value = trueValue(sensor);
        if (fault != null && fault.mode.equals("stuck")) {  // a stuck sensor does not know it is stuck: it reports ok
            if (fault.stuckValue == null) {
                fault.stuckValue = value;
            }
            return new Reading(fault.stuckValue, ReadingQuality.OK);
        }
        return new Reading(value, ReadingQuality.OK);
    }

    private double trueValue(SensorSpec sensor) {
        double[] air = ambient();
        double daily = Math.sin(2 * Math.PI * (localHour() - 9) / 24);
        return switch (sensor.kind()) {
            case MOISTURE -> Numbers.round(Math.min(100.0, Math.max(0.0, moisture.get(sensor.id()) + rng.nextGaussian() * 0.4)), 1);
            case TEMPERATURE -> Numbers.round(air[0] + rng.nextGaussian() * 0.15, 1);
            case HUMIDITY -> Numbers.round(Math.min(100.0, air[1] + rng.nextGaussian() * 1.0), 1);
            case CRACK_WIDTH -> Numbers.round(crackMm - 0.02 * daily + rng.nextGaussian() * 0.004, 3);  // closes a little in the heat
            case TILT -> Numbers.round(tiltDeg + 0.003 * daily + rng.nextGaussian() * 0.001, 3);
        };
    }

    synchronized double moistureOf(String sensorId) {
        return moisture.get(sensorId);
    }

    // B01-W01-R1C3 -> 1
    static int row(String zoneId) {
        String rest = zoneId.substring(zoneId.lastIndexOf("-R") + 2);
        return Integer.parseInt(rest.substring(0, rest.indexOf('C')));
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }
}
