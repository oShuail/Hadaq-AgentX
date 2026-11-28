package com.example.hadaq.Service;

import com.example.hadaq.DTOOut.HistoryPointDTOOut;
import com.example.hadaq.DTOOut.LatestReadingDTOOut;
import com.example.hadaq.DTOOut.SensorOverviewDTOOut;
import com.example.hadaq.DTOOut.WindowStatsDTOOut;
import com.example.hadaq.Entity.SensorReading;
import com.example.hadaq.Enum.ReadingQuality;
import com.example.hadaq.Enum.SensorKind;
import com.example.hadaq.Enum.SensorStatus;
import com.example.hadaq.Mapper.DTOMapper;
import com.example.hadaq.Model.HealthResult;
import com.example.hadaq.Model.SensorSpec;
import com.example.hadaq.Util.Numbers;
import com.example.hadaq.Util.Times;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/*
 * Sensor health and trends (port of hadaq_api.sensors). Pure functions over stored readings, newest first.
 *
 *   ok        recent, plausible, varying readings
 *   suspect   the device says "suspect", the value is outside the plausible range for its kind, or it is stuck:
 *             the same value FLATLINE_N times in a row while another sensor of the same kind is moving
 *             (a calm wall where everything is flat is not flagged)
 *   fault     the device itself reported a fault
 *   stale     no reading for sensor-stale-seconds (sensor or device offline, battery, radio)
 *   no_data   never reported
 *
 * The thresholds are ASSUMED for the demo cadence (one reading every 2 s); tune them for real nodes.
 */
@Service
public class SensorHealthService {

    public static final int FLATLINE_N = 12;

    private static final Map<SensorKind, double[]> PLAUSIBLE = Map.of(
            SensorKind.MOISTURE, new double[]{0.0, 100.0},
            SensorKind.TEMPERATURE, new double[]{-10.0, 60.0},
            SensorKind.HUMIDITY, new double[]{0.0, 100.0},
            SensorKind.CRACK_WIDTH, new double[]{0.0, 20.0},
            SensorKind.TILT, new double[]{-10.0, 10.0});

    // smallest change over the window that counts as a trend rather than noise
    private static final Map<SensorKind, Double> TREND_MIN_DELTA = Map.of(
            SensorKind.MOISTURE, 2.0,
            SensorKind.TEMPERATURE, 1.0,
            SensorKind.HUMIDITY, 3.0,
            SensorKind.CRACK_WIDTH, 0.03,
            SensorKind.TILT, 0.005);

    @Getter
    private final double staleAfterSeconds;

    public SensorHealthService(@Value("${hadaq.sensor-stale-seconds:30}") double staleAfterSeconds) {
        this.staleAfterSeconds = staleAfterSeconds;
    }

    public static boolean plausible(SensorKind kind, double value) {
        double[] range = PLAUSIBLE.get(kind);
        return range == null || (value >= range[0] && value <= range[1]);
    }

    public static boolean usable(SensorReading reading) {
        return reading.getQuality() == ReadingQuality.OK && plausible(reading.getKind(), reading.getValue());
    }

    // the last FLATLINE_N readings (newest first) all have the same value
    public static boolean isFlat(List<SensorReading> history) {
        if (history.size() < FLATLINE_N) {
            return false;
        }
        double first = history.get(0).getValue();
        return history.subList(0, FLATLINE_N).stream().allMatch(r -> r.getValue() == first);
    }

    // sensor_id of another sensor of the same kind that changed noticeably since `since`, if any
    static String peersMoving(SensorKind kind, Instant since, List<List<SensorReading>> peers) {
        double minDelta = TREND_MIN_DELTA.getOrDefault(kind, 0.0);
        for (List<SensorReading> history : peers) {
            List<Double> values = history.stream()
                    .filter(r -> usable(r) && !r.getTs().isBefore(since))
                    .map(SensorReading::getValue).toList();
            if (values.size() >= 2 && Collections.max(values) - Collections.min(values) >= minDelta) {
                return history.get(0).getSensorId();
            }
        }
        return null;
    }

    // status and human-readable reasons. peers: histories of the other sensors of the same kind (newest first)
    public HealthResult health(SensorSpec spec, List<SensorReading> history, Instant now, List<List<SensorReading>> peers) {
        if (history.isEmpty()) {
            return new HealthResult(SensorStatus.NO_DATA, List.of("no reading yet"));
        }
        SensorReading latest = history.get(0);
        SensorStatus status = SensorStatus.OK;
        List<String> reasons = new ArrayList<>();
        String unit = spec != null && spec.unit() != null ? spec.unit() : latest.getUnit();

        if (latest.getQuality() == ReadingQuality.FAULT) {
            status = SensorStatus.FAULT;
            reasons.add("the device reported a fault");
        } else if (!plausible(latest.getKind(), latest.getValue())) {
            double[] range = PLAUSIBLE.get(latest.getKind());
            status = SensorStatus.SUSPECT;
            reasons.add("value " + py(latest.getValue()) + " " + unit + " is outside the plausible range "
                    + g(range[0]) + " to " + g(range[1]));
        } else if (latest.getQuality() == ReadingQuality.SUSPECT) {
            status = SensorStatus.SUSPECT;
            reasons.add("the device marked the reading as suspect");
        } else if (peers != null && !peers.isEmpty() && isFlat(history)) {
            String moving = peersMoving(latest.getKind(), history.get(FLATLINE_N - 1).getTs(), peers);
            if (moving != null) {
                status = SensorStatus.SUSPECT;
                reasons.add("the same value for " + FLATLINE_N + " readings in a row while " + moving
                        + " is changing (possibly stuck)");
            }
        }
        double age = seconds(latest.getTs(), now);
        if (age > staleAfterSeconds) {
            status = SensorStatus.STALE;
            reasons.add(0, "no reading for " + (long) age + " s");
        }
        return new HealthResult(status, reasons);
    }

    // trend over the readings in `history` (newest first), using only usable readings
    public WindowStatsDTOOut windowStats(SensorSpec spec, List<SensorReading> history) {
        List<SensorReading> good = history.stream().filter(SensorHealthService::usable).toList();
        if (good.isEmpty()) {
            return null;
        }
        SensorReading first = good.get(good.size() - 1);
        SensorReading last = good.get(0);
        double minutes = Math.max(seconds(first.getTs(), last.getTs()) / 60.0, 1e-9);
        double change = last.getValue() - first.getValue();
        SensorKind kind = spec != null && spec.kind() != null ? spec.kind() : last.getKind();
        double minDelta = TREND_MIN_DELTA.getOrDefault(kind, 0.0);
        String trend = "steady";
        if (good.size() >= 3 && Math.abs(change) >= minDelta) {
            trend = change > 0 ? "rising" : "falling";
        }
        int digits = last.getKind() == SensorKind.CRACK_WIDTH || last.getKind() == SensorKind.TILT ? 3 : 1;
        // like Python min()/max(): the first one in the list wins a tie
        SensorReading lowest = good.stream().min(Comparator.comparingDouble(SensorReading::getValue)).orElseThrow();
        SensorReading highest = good.stream().reduce((a, b) -> b.getValue() > a.getValue() ? b : a).orElseThrow();
        double mean = good.stream().mapToDouble(SensorReading::getValue).average().orElse(0);
        return new WindowStatsDTOOut(
                good.size(),
                history.size() - good.size(),
                first.getTs(),
                last.getTs(),
                first.getValue(),
                last.getValue(),
                lowest.getValue(),
                highest.getValue(),
                Numbers.round(mean, digits),
                Numbers.round(change, digits),
                good.size() > 1 ? Numbers.round(change / minutes, digits + 1) : 0.0,
                trend,
                first.getEvidenceId(),
                last.getEvidenceId(),
                highest.getEvidenceId());
    }

    // everything the UI and the agents need about one sensor. history: newest first, window already applied
    public SensorOverviewDTOOut overview(SensorSpec spec, List<SensorReading> history, List<List<SensorReading>> peers) {
        Instant now = Times.now();
        HealthResult health = health(spec, history, now, peers);
        SensorReading latest = history.isEmpty() ? null : history.get(0);
        LatestReadingDTOOut latestDto = latest == null ? null : new LatestReadingDTOOut(
                latest.getValue(), latest.getTs(), Numbers.round(seconds(latest.getTs(), now), 1),
                latest.getQuality(), latest.getProvenance(), latest.getEvidenceId());
        return new SensorOverviewDTOOut(spec.id(), spec.label(), spec.kind(), spec.unit(), spec.zoneId(),
                spec.deviceId(), health.status(), health.reasons(), latestDto, windowStats(spec, history));
    }

    // oldest first, at most `points` readings evenly spread (keeps the newest)
    public static List<HistoryPointDTOOut> downsample(List<SensorReading> history, int points) {
        List<SensorReading> ordered = new ArrayList<>(history);
        Collections.reverse(ordered);
        List<SensorReading> picked;
        if (points <= 0 || ordered.size() <= points) {
            picked = ordered;
        } else {
            double step = (ordered.size() - 1) / (double) (points - 1);
            picked = new ArrayList<>();
            for (int i = 0; i < points; i++) {
                picked.add(ordered.get((int) Math.rint(i * step)));
            }
        }
        return picked.stream().map(DTOMapper::toHistoryPoint).toList();
    }

    private static double seconds(Instant from, Instant to) {
        return Duration.between(from, to).toNanos() / 1e9;
    }

    // Python str(float): 41.2, 120.0
    private static String py(double value) {
        return Double.toString(value);
    }

    // Python "{:g}": 0, 100, -10, 0.5
    private static String g(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%d", (long) value);
        }
        return Double.toString(value);
    }
}
