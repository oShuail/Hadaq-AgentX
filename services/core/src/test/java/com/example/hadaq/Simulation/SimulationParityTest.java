package com.example.hadaq.Simulation;

import com.example.hadaq.DTOIn.SimulationRequestDTOIn;
import com.example.hadaq.DTOOut.RiskScoreDTOOut;
import com.example.hadaq.DTOOut.SimulationResultDTOOut;
import com.example.hadaq.DTOOut.ZoneSummaryDTOOut;
import com.example.hadaq.Entity.Detection;
import com.example.hadaq.Enum.Scenario;
import com.example.hadaq.Service.WeatherService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/*
 * The Java port must give the same numbers as Basma's Python engine (hadaq_sim).
 * src/test/resources/golden/sim_golden.json was produced by running the Python code on the same inputs.
 */
class SimulationParityTest {

    private static final double EPS = 1e-9;
    private static JsonNode golden;
    private static JsonNode registry;

    @BeforeAll
    static void load() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        try (InputStream in = new ClassPathResource("golden/sim_golden.json").getInputStream()) {
            golden = mapper.readTree(in);
        }
        try (InputStream in = new ClassPathResource("contracts/building_b01.json").getInputStream()) {
            registry = mapper.readTree(in);
        }
    }

    @Test
    void weatherModelAndRiskMatchPython() {
        OffsetDateTime start = OffsetDateTime.parse(golden.path("start").asString());
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : registry.path("zones").path("value_weight_overrides").properties()) {
            weights.put(e.getKey(), e.getValue().asDouble());
        }
        double defaultWeight = registry.path("zones").path("value_weight_default").asDouble();
        double heavyRain = registry.path("site").path("heavy_rain_days_per_year").asDouble();

        for (Map.Entry<String, JsonNode> entry : golden.path("runs").properties()) {
            String name = entry.getKey();
            JsonNode run = entry.getValue();

            List<WeatherHour> weather = WeatherService.syntheticStorm(start, 72, run.path("peak").asDouble(),
                    run.path("wind").asDouble(), run.path("wind_dir").asDouble(), 10.0, 18.0);
            JsonNode expectedWeather = run.path("weather");
            assertEquals(expectedWeather.size(), weather.size(), name + " hours");
            for (int i = 0; i < weather.size(); i++) {
                JsonNode h = expectedWeather.get(i);
                assertEquals(h.path("rain_mm").asDouble(), weather.get(i).rainMm(), EPS, name + " rain h" + i);
                assertEquals(h.path("wind_ms").asDouble(), weather.get(i).windMs(), EPS, name + " wind h" + i);
                assertEquals(h.path("temp_c").asDouble(), weather.get(i).tempC(), EPS, name + " temp h" + i);
                assertEquals(h.path("rh").asDouble(), weather.get(i).rh(), EPS, name + " rh h" + i);
            }

            SimulationRequestDTOIn request = new SimulationRequestDTOIn("B01", "W01",
                    Scenario.fromValue(run.path("scenario").asString()), null, "test");
            SimulationResultDTOOut sim = MoistureModel.run(request, weather, new WallParams(270, 4, 8), "golden");

            for (Map.Entry<String, JsonNode> z : run.path("summary").properties()) {
                ZoneSummaryDTOOut s = sim.summary().get(z.getKey());
                assertEquals(z.getValue().path("peak_moisture").asDouble(), s.peakMoisture(), EPS, name + " peak " + z.getKey());
                assertEquals(z.getValue().path("hours_above_threshold").asInt(), s.hoursAboveThreshold(), name + " hours " + z.getKey());
                assertEquals(z.getValue().path("erosion_index").asDouble(), s.erosionIndex(), EPS, name + " erosion " + z.getKey());
            }
            for (Map.Entry<String, JsonNode> z : run.path("zones_last").properties()) {
                List<Double> series = sim.zones().get(z.getKey());
                assertEquals(z.getValue().asDouble(), series.get(series.size() - 1), EPS, name + " last " + z.getKey());
            }
            List<String> assumptions = new ArrayList<>();
            run.path("assumptions").forEach(a -> assumptions.add(a.asString()));
            assertEquals(assumptions, sim.assumptions(), name + " assumptions text");

            Detection crack = new Detection();
            crack.setDetectionId("det_golden");
            crack.setZoneId("B01-W01-R2C5");
            crack.setGrowthPct(18.5);
            List<RiskScoreDTOOut> scores = RiskScorer.scoreBuilding(sim, List.of(crack), heavyRain, weights, defaultWeight);
            JsonNode expectedRisk = run.path("risk");
            assertEquals(expectedRisk.size(), scores.size(), name + " risk count");
            for (int i = 0; i < scores.size(); i++) {
                JsonNode r = expectedRisk.get(i);
                RiskScoreDTOOut s = scores.get(i);
                assertEquals(r.path("zone_id").asString(), s.zoneId(), name + " risk order #" + i);
                assertEquals(r.path("A").asDouble(), s.a(), EPS);
                assertEquals(r.path("B").asDouble(), s.b(), EPS, name + " B " + s.zoneId());
                assertEquals(r.path("C").asDouble(), s.c(), EPS, name + " C " + s.zoneId());
                assertEquals(r.path("magnitude").asDouble(), s.magnitude(), EPS, name + " magnitude " + s.zoneId());
                assertEquals(r.path("level").asString(), s.level().value(), name + " level " + s.zoneId());
            }
        }
    }

    @Test
    void moistureRuleMatchesPython() {
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        JsonNode expected = golden.path("anomaly");

        Map<String, Object> absolute = MonitoringRule.detectMoistureAnomaly(List.of(
                new MonitoringRule.TimedValue(t0, 40.0), new MonitoringRule.TimedValue(t0.plus(Duration.ofMinutes(5)), 61.0)));
        assertEquals(expected.path("absolute").path("rule").asString(), absolute.get("rule"));
        assertEquals(expected.path("absolute").path("value").asDouble(), (double) absolute.get("value"), EPS);

        Map<String, Object> rate = MonitoringRule.detectMoistureAnomaly(List.of(
                new MonitoringRule.TimedValue(t0, 30.0), new MonitoringRule.TimedValue(t0.plus(Duration.ofMinutes(30)), 36.0)));
        assertEquals(expected.path("rate").path("rule").asString(), rate.get("rule"));
        assertEquals(expected.path("rate").path("rate_pct_per_h").asDouble(), (double) rate.get("rate_pct_per_h"), EPS);

        assertNull(MonitoringRule.detectMoistureAnomaly(List.of(
                new MonitoringRule.TimedValue(t0, 30.0), new MonitoringRule.TimedValue(t0.plus(Duration.ofMinutes(30)), 31.0))));
        assertEquals(true, expected.path("none").isNull());
        // a 10-minute window is too short for the rate rule
        assertNull(MonitoringRule.detectMoistureAnomaly(List.of(
                new MonitoringRule.TimedValue(t0, 30.0), new MonitoringRule.TimedValue(t0.plus(Duration.ofMinutes(10)), 40.0))));
        assertEquals(true, expected.path("too_short").isNull());
    }
}
