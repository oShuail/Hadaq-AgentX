package com.example.hadaq.Controller;

import com.example.hadaq.Repository.DetectionRepository;
import com.example.hadaq.Repository.EventRepository;
import com.example.hadaq.Repository.EvidenceRepository;
import com.example.hadaq.Repository.PhotoRepository;
import com.example.hadaq.Repository.SensorReadingRepository;
import com.example.hadaq.Repository.SimulationRunRepository;
import com.example.hadaq.Service.AnomalyCooldownService;
import com.example.hadaq.Service.RiskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// the HTTP API exactly as contracts/README.md describes it (paths, snake_case fields, status codes)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private SensorReadingRepository readings;
    @Autowired
    private EventRepository events;
    @Autowired
    private PhotoRepository photos;
    @Autowired
    private DetectionRepository detections;
    @Autowired
    private SimulationRunRepository simulations;
    @Autowired
    private EvidenceRepository evidence;
    @Autowired
    private AnomalyCooldownService cooldown;
    @Autowired
    private RiskService riskService;

    @BeforeEach
    void clean() {
        readings.deleteAll();
        events.deleteAll();
        detections.deleteAll();
        photos.deleteAll();
        simulations.deleteAll();
        evidence.deleteAll();
        cooldown.clear();
        riskService.invalidate("B01");
    }

    private String reading(String sensorId, String zoneId, String kind, double value, String unit, String deviceId, Instant ts) {
        return """
                {"sensor_id": "%s", "building_id": "B01", "zone_id": "%s", "kind": "%s", "value": %s, "unit": "%s",
                 "ts": "%s", "provenance": "synthetic", "device_id": "%s", "quality": "ok"}
                """.formatted(sensorId, zoneId, kind, value, unit, ts, deviceId);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void healthAndRegistry() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")))
                .andExpect(jsonPath("$.mqtt", is(false)));
        mvc.perform(get("/buildings/B01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.building.id", is("B01")))
                .andExpect(jsonPath("$.sensors", hasSize(9)))
                .andExpect(jsonPath("$.walls[0].azimuth_deg", is(270)));
        mvc.perform(get("/buildings/B99")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Unknown building B99")));
    }

    @Test
    void readingsSensorsDevicesAndZones() throws Exception {
        Instant now = Instant.now();
        mvc.perform(post("/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(reading("B01-S04", "B01-W01-R1C6", "moisture", 41.2, "%rel", "sim-01", now.minusSeconds(4))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stored", is(true)));
        mvc.perform(post("/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(reading("B01-S04", "B01-W01-R1C6", "moisture", 44.0, "%rel", "sim-01", now.minusSeconds(2))))
                .andExpect(status().isOk());
        mvc.perform(post("/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(reading("B01-S04", "B01-W01-R1C6", "moisture", 47.5, "%rel", "sim-01", now)))
                .andExpect(status().isOk());

        mvc.perform(get("/buildings/B01/readings/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sensor_id", is("B01-S04")))
                .andExpect(jsonPath("$[0].value", is(47.5)))
                .andExpect(jsonPath("$[0].evidence_id", startsWith("rd_")));
        mvc.perform(get("/buildings/B01/readings").param("sensor_id", "B01-S04").param("limit", "2"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].value", is(47.5)));

        MvcResult sensors = mvc.perform(get("/buildings/B01/sensors")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(9))).andReturn();
        JsonNode s04 = null;
        for (JsonNode s : json(sensors)) {
            if (s.path("sensor_id").asString().equals("B01-S04")) {
                s04 = s;
            }
        }
        assertEquals("ok", s04.path("status").asString());
        assertEquals("rising", s04.path("window").path("trend").asString());
        assertEquals(3, s04.path("window").path("readings").asInt());
        assertTrue(s04.path("latest").path("evidence_id").asString().startsWith("rd_"));
        assertTrue(s04.path("latest").has("age_s"));

        mvc.perform(get("/buildings/B01/sensors/B01-S04").param("points", "2"))
                .andExpect(jsonPath("$.history", hasSize(2)))
                .andExpect(jsonPath("$.history[1].value", is(47.5)));
        mvc.perform(get("/buildings/B01/sensors/B01-S99")).andExpect(status().isNotFound());

        mvc.perform(get("/buildings/B01/devices"))
                .andExpect(jsonPath("$[?(@.device_id == 'sim-01')].status", hasItem("online")))
                .andExpect(jsonPath("$[?(@.device_id == 'esp32-01')].status", hasItem("never_seen")));

        mvc.perform(get("/buildings/B01/zones/B01-W01-R1C6")).andExpect(status().isOk())
                .andExpect(jsonPath("$.position", is("base")))
                .andExpect(jsonPath("$.row", is(1)))
                .andExpect(jsonPath("$.sensors[0].sensor_id", is("B01-S04")))
                .andExpect(jsonPath("$.risk", nullValue()));
        mvc.perform(get("/buildings/B01/zones/B01-W01-R9C9")).andExpect(status().isNotFound());
        mvc.perform(get("/buildings/B01/zones/nonsense")).andExpect(status().isNotFound());
    }

    @Test
    void wetBaseRaisesOneMoistureAnomaly() throws Exception {
        Instant now = Instant.now();
        mvc.perform(post("/readings").contentType(MediaType.APPLICATION_JSON)
                .content(reading("B01-S07", "B01-W01-R1C1", "moisture", 50.0, "%rel", "sim-01", now.minusSeconds(2))));
        mvc.perform(post("/readings").contentType(MediaType.APPLICATION_JSON)
                .content(reading("B01-S07", "B01-W01-R1C1", "moisture", 63.0, "%rel", "sim-01", now)));
        // a second wet reading inside the cooldown does not fire again
        mvc.perform(post("/readings").contentType(MediaType.APPLICATION_JSON)
                .content(reading("B01-S07", "B01-W01-R1C1", "moisture", 64.0, "%rel", "sim-01", now.plusSeconds(1))));

        mvc.perform(get("/buildings/B01/events"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type", is("moisture_anomaly")))
                .andExpect(jsonPath("$[0].source", is("monitoring")))
                .andExpect(jsonPath("$[0].provenance", is("synthetic")))
                .andExpect(jsonPath("$[0].payload.rule", is("absolute")))
                .andExpect(jsonPath("$[0].payload.sensor_id", is("B01-S07")))
                .andExpect(jsonPath("$[0].payload.reading_evidence_id", startsWith("rd_")))
                .andExpect(jsonPath("$[0].event_id", startsWith("evt_")));
    }

    @Test
    void eventsAreStoredOnceAndValidated() throws Exception {
        String body = """
                {"event_id": "evt_3f9a1c2b7d4e", "type": "storm_started", "building_id": "B01",
                 "ts": "2026-11-20T10:16:00+03:00", "source": "scenario_panel",
                 "payload": {"peak_rain_mm_h": 12, "wind_ms": 8, "wind_dir_deg": 270}, "provenance": "synthetic"}
                """;
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_id", is("evt_3f9a1c2b7d4e")))
                .andExpect(jsonPath("$.ts", is("2026-11-20T07:16:00Z")))
                .andExpect(jsonPath("$.payload.peak_rain_mm_h", is(12)));
        // the same event again (MQTT echo) is not stored twice
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(get("/buildings/B01/events")).andExpect(jsonPath("$", hasSize(1)));

        // microseconds survive the database (MariaDB + the MySQL driver used to drop them)
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"type": "storm_ended", "building_id": "B01", "ts": "2026-11-20T10:17:00.123456+03:00",
                         "source": "scenario_panel", "provenance": "synthetic"}
                        """))
                .andExpect(status().isOk());
        mvc.perform(get("/buildings/B01/events"))
                .andExpect(jsonPath("$[0].type", is("storm_ended")))
                .andExpect(jsonPath("$[0].ts", is("2026-11-20T07:17:00.123456Z")));

        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"party\", \"building_id\": \"B01\", \"source\": \"api\", \"provenance\": \"synthetic\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"demo_reset\", \"building_id\": \"B01\", \"source\": \"scenario_panel\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("provenance is required")));
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"demo_reset\", \"building_id\": \"B07\", \"source\": \"scenario_panel\", \"provenance\": \"synthetic\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void photoUploadDetectionsAndEvidence() throws Exception {
        // a plain wall-coloured image (no faces): the privacy check runs and hides nothing
        nu.pattern.OpenCV.loadLocally();
        Mat wall = new Mat(120, 160, CvType.CV_8UC3, new Scalar(90, 140, 190));
        MatOfByte png = new MatOfByte();
        Imgcodecs.imencode(".png", wall, png);
        MockMultipartFile file = new MockMultipartFile("file", "frame.png", "image/png", png.toArray());

        MvcResult upload = mvc.perform(multipart("/photos").file(file)
                        .param("camera_id", "B01-CAM01").param("building_id", "B01")
                        .param("provenance", "synthetic").param("sequence", "baseline").param("frame", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photo_id", startsWith("ph_")))
                .andExpect(jsonPath("$.status", is("new")))
                .andExpect(jsonPath("$.frame", is(3)))
                .andReturn();
        String photoId = json(upload).path("photo_id").asString();
        assertTrue(json(upload).path("file_url").asString().endsWith("/photos/" + photoId + "/file"));

        mvc.perform(get("/photos/" + photoId + "/file")).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        mvc.perform(get("/buildings/B01/photos").param("status", "new"))
                .andExpect(jsonPath("$[0].photo_id", is(photoId)));
        mvc.perform(get("/buildings/B01/events"))
                .andExpect(jsonPath("$[0].type", is("new_photo")))
                .andExpect(jsonPath("$[0].payload.privacy.checked", is(true)))
                .andExpect(jsonPath("$[0].payload.privacy.faces_hidden", is(0)));

        String batch = """
                {"photo_id": "%s", "building_id": "B01", "camera_id": "B01-CAM01", "model": "mock-detector",
                 "detections": [{"detection_id": "det_77aa01bb2c3d", "photo_id": "%s", "camera_id": "B01-CAM01",
                   "building_id": "B01", "zone_id": "B01-W01-R2C5", "cls": "crack", "confidence": 0.81,
                   "bbox_xyxy": [1820.0, 1210.0, 1905.0, 1530.0], "polygon": null, "length_px": 320.0,
                   "area_px": 2150.0, "model": "mock-detector", "provenance": "synthetic", "change": "grown", "growth_pct": 18.5}]}
                """.formatted(photoId, photoId);
        mvc.perform(post("/detections").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stored", is(1)));

        mvc.perform(get("/buildings/B01/detections/latest"))
                .andExpect(jsonPath("$[0].detection_id", is("det_77aa01bb2c3d")))
                .andExpect(jsonPath("$[0].bbox_xyxy", hasSize(4)))
                .andExpect(jsonPath("$[0].change", is("grown")))
                .andExpect(jsonPath("$[0].growth_pct", is(18.5)));
        mvc.perform(get("/buildings/B01/photos")).andExpect(jsonPath("$[0].status", is("processed")));
        mvc.perform(get("/buildings/B01/events"))
                .andExpect(jsonPath("$[0].type", is("detection_ready")))
                .andExpect(jsonPath("$[0].provenance", is("synthetic")))
                .andExpect(jsonPath("$[0].payload.changed[0].zone_id", is("B01-W01-R2C5")))
                .andExpect(jsonPath("$[0].payload.changed[0].change", is("grown")));

        mvc.perform(get("/evidence/det_77aa01bb2c3d")).andExpect(jsonPath("$.kind", is("detection")));
        mvc.perform(get("/evidence/" + photoId)).andExpect(jsonPath("$.kind", is("photo")));
        mvc.perform(get("/evidence/rd_999999")).andExpect(status().isNotFound());
        mvc.perform(get("/evidence/nope")).andExpect(status().isNotFound());

        // a second photo of the same camera sees the first one's detections
        MvcResult second = mvc.perform(multipart("/photos").file(file)
                .param("camera_id", "B01-CAM01").param("building_id", "B01")).andReturn();
        mvc.perform(get("/photos/" + json(second).path("photo_id").asString() + "/previous-detections"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].detection_id", is("det_77aa01bb2c3d")));

        // bad batch: confidence above 1
        mvc.perform(post("/detections").contentType(MediaType.APPLICATION_JSON)
                        .content(batch.replace("0.81", "1.7")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simulationRiskAndEvidence() throws Exception {
        mvc.perform(get("/buildings/B01/simulations/latest")).andExpect(status().isNotFound());
        mvc.perform(get("/buildings/B01/risk")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

        String request = """
                {"building_id": "B01", "wall_id": "W01", "scenario": "drainage_fixed",
                 "weather": {"mode": "synthetic_storm", "hours": 72, "peak_rain_mm_h": 12, "wind_ms": 8,
                             "wind_dir_deg": 270, "rain_multiplier": 1.0}, "requested_by": "agent"}
                """;
        MvcResult started = mvc.perform(post("/simulations").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("running")))
                .andReturn();
        String runId = json(started).path("run_id").asString();
        assertTrue(runId.startsWith("sim_"));

        JsonNode run = null;
        for (int i = 0; i < 100; i++) {
            run = json(mvc.perform(get("/simulations/" + runId)).andReturn());
            if (!run.path("status").asString().equals("running")) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals("done", run.path("status").asString(), "simulation finished: " + run);
        assertEquals("bucket_v0", run.path("result").path("engine").asString());
        assertEquals(32, run.path("result").path("summary").size());
        assertEquals(72, run.path("result").path("zones").path("B01-W01-R1C1").size());
        assertEquals("agent", run.path("request").path("requested_by").asString());

        mvc.perform(get("/buildings/B01/simulations/latest")).andExpect(jsonPath("$.run_id", is(runId)));
        MvcResult risk = mvc.perform(get("/buildings/B01/risk"))
                .andExpect(jsonPath("$", hasSize(32)))
                .andExpect(jsonPath("$[0].A", notNullValue()))
                .andExpect(jsonPath("$[0].hazard", is("rain")))
                .andExpect(jsonPath("$[*].level", everyItem(isOneOf("low", "medium", "high", "very_high"))))
                .andExpect(jsonPath("$[0].evidence_ids[0]", is(runId)))
                .andReturn();
        String riskId = json(risk).get(0).path("risk_id").asString();
        mvc.perform(get("/evidence/" + riskId)).andExpect(jsonPath("$.kind", is("risk")))
                .andExpect(jsonPath("$.value.zone_id", notNullValue()));
        mvc.perform(get("/evidence/" + runId)).andExpect(jsonPath("$.kind", is("simulation")))
                .andExpect(jsonPath("$.status", is("done")))
                .andExpect(jsonPath("$.value.engine", is("bucket_v0")));
        mvc.perform(get("/buildings/B01/zones/B01-W01-R1C4"))
                .andExpect(jsonPath("$.simulation.run_id", is(runId)))
                .andExpect(jsonPath("$.risk.zone_id", is("B01-W01-R1C4")));
        mvc.perform(get("/buildings/B01/events"))
                .andExpect(jsonPath("$[0].type", is("simulation_done")))
                .andExpect(jsonPath("$[0].payload.scenario", is("drainage_fixed")));

        mvc.perform(get("/simulations/sim_doesnotexist")).andExpect(status().isNotFound());
        mvc.perform(post("/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"building_id\": \"B01\", \"weather\": {\"hours\": 2}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sensorRunNeedsTheSimulator() throws Exception {
        mvc.perform(get("/buildings/B01/demo/sensor-run"))
                .andExpect(jsonPath("$.available", is(false)))
                .andExpect(jsonPath("$.running", is(false)))
                .andExpect(jsonPath("$.duration", is(120.0)));
        mvc.perform(post("/buildings/B01/demo/sensor-run")).andExpect(status().isServiceUnavailable());

        // once sim-01 sends readings it is online and the run starts
        mvc.perform(post("/readings").contentType(MediaType.APPLICATION_JSON)
                .content(reading("B01-S05", "B01-W01-R3C4", "moisture", 20.0, "%rel", "sim-01", Instant.now())));
        mvc.perform(post("/buildings/B01/demo/sensor-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running", is(true)));
        mvc.perform(get("/buildings/B01/events"))
                .andExpect(jsonPath("$[*].type", hasItem("storm_started")))
                .andExpect(jsonPath("$[*].type", hasItem("demo_reset")));
        mvc.perform(post("/buildings/B01/demo/sensor-run/stop"))
                .andExpect(jsonPath("$.running", is(false)));
    }
}
