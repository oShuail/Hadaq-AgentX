package com.example.hadaq.Simulation;

import com.example.hadaq.Util.Numbers;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Monitoring rule v0 for moisture (%rel, 0..100), port of hadaq_sim.monitoring (owner: Basma):
 * - fires if the value is above ABS_THRESHOLD, or
 * - fires if it rose faster than RISE_PCT_PER_H compared with the reading about an hour earlier.
 * Both thresholds are ASSUMED.
 */
public final class MonitoringRule {

    public static final double ABS_THRESHOLD = 60.0;
    public static final double RISE_PCT_PER_H = 8.0;

    public record TimedValue(Instant ts, double value) {
    }

    private MonitoringRule() {
    }

    // history: any order. Returns the anomaly details (event payload), or null
    public static Map<String, Object> detectMoistureAnomaly(List<TimedValue> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        List<TimedValue> points = history.stream().sorted(Comparator.comparing(TimedValue::ts)).toList();
        TimedValue latest = points.get(points.size() - 1);
        Map<String, Object> out = new LinkedHashMap<>();
        if (latest.value() >= ABS_THRESHOLD) {
            out.put("rule", "absolute");
            out.put("value", latest.value());
            out.put("threshold", ABS_THRESHOLD);
            return out;
        }
        Instant windowStart = latest.ts().minus(Duration.ofHours(1));
        List<TimedValue> earlier = points.stream().filter(p -> !p.ts().isAfter(windowStart)).toList();
        TimedValue ref = earlier.isEmpty() ? points.get(0) : earlier.get(earlier.size() - 1);
        double hours = Math.max(Duration.between(ref.ts(), latest.ts()).toNanos() / 3.6e12, 1.0 / 60);
        double rate = (latest.value() - ref.value()) / hours;
        if (rate >= RISE_PCT_PER_H && hours >= 0.25) {
            out.put("rule", "rate");
            out.put("value", latest.value());
            out.put("rate_pct_per_h", Numbers.round(rate, 2));
            out.put("threshold", RISE_PCT_PER_H);
            return out;
        }
        return null;
    }
}
