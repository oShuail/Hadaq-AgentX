package com.example.hadaq.Controller;

import com.example.hadaq.DTOIn.SimulationRequestDTOIn;
import com.example.hadaq.Enum.Scenario;
import com.example.hadaq.Simulation.MoistureModel;
import com.example.hadaq.Simulation.WallParams;
import com.example.hadaq.Service.WeatherService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// T5: POST /api/v2/simulations goes to Basma's /sim/run when SIM_URL is set, and falls back to the Java model
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SimServiceForwardTest {

    static final BlockingQueue<String> REQUESTS = new LinkedBlockingQueue<>();
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicReference<String> ANSWER = new AtomicReference<>("{}");
    static HttpServer simService;

    @DynamicPropertySource
    static void sim(DynamicPropertyRegistry registry) throws IOException {
        simService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        simService.createContext("/sim/run", exchange -> {
            // like a strict server: no Content-Length (a chunked body) = 411, so the core must send the length
            if (exchange.getRequestHeaders().getFirst("Content-Length") == null) {
                exchange.sendResponseHeaders(411, -1);
                exchange.close();
                return;
            }
            REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ANSWER.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(STATUS.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        simService.start();
        registry.add("hadaq.sim.url", () -> "http://127.0.0.1:" + simService.getAddress().getPort() + "/");
    }

    @AfterAll
    static void stop() {
        simService.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper jsonMapper;

    @BeforeEach
    void clean() {
        REQUESTS.clear();
        STATUS.set(200);
    }

    static final String REQUEST = """
            {"building_id": "B01", "wall_id": "W01", "scenario": "drainage_fixed",
             "weather": {"mode": "synthetic_storm", "hours": 24}, "requested_by": "web"}
            """;

    JsonNode runAndWait() throws Exception {
        String runId = jsonMapper.readTree(mvc.perform(post("/api/v2/simulations").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("run_id").asString();
        JsonNode run = null;
        for (int i = 0; i < 100; i++) {
            // v2 only adds POST /api/v2/simulations; the result is read on the v1 route of the same server
            run = jsonMapper.readTree(mvc.perform(get("/simulations/" + runId)).andReturn().getResponse().getContentAsString());
            if (!"running".equals(run.path("status").asString())) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals(runId, run.path("result").path("run_id").asString(), "the core's run id is kept");
        return run;
    }

    // what Basma's service might answer: a SimulationResult with its own engine name, run id and extras
    String serviceAnswer() {
        SimulationRequestDTOIn req = new SimulationRequestDTOIn("B01", "W01", Scenario.DRAINAGE_FIXED, null, "web");
        ObjectNode answer = jsonMapper.valueToTree(MoistureModel.run(req,
                WeatherService.syntheticStorm(OffsetDateTime.parse("2026-01-01T00:00:00+03:00"), 24, 12, 8, 270, 10, 18),
                new WallParams(270, 4, 8), "Open-Meteo forecast"));
        answer.put("engine", "bucket_v1");
        answer.put("run_id", "sim_theirs00000");
        answer.putObject("first_critical_hour").put("B01-W01-R1C4", 9);
        return answer.toString();
    }

    @Test
    void forwardsToTheSimulationService() throws Exception {
        ANSWER.set(serviceAnswer());
        JsonNode run = runAndWait();
        assertEquals("done", run.path("status").asString());
        assertEquals("bucket_v1", run.path("result").path("engine").asString(), "the service's result is used");
        assertEquals(9, run.path("result").path("first_critical_hour").path("B01-W01-R1C4").asInt(), "extras are kept");

        JsonNode sent = jsonMapper.readTree(REQUESTS.poll(5, TimeUnit.SECONDS));
        assertEquals("B01", sent.path("building_id").asString());
        assertEquals("drainage_fixed", sent.path("scenario").asString());
        assertEquals(32.8, sent.path("wall").path("width_m").asDouble());
        assertEquals("synthetic_storm", sent.path("weather").path("mode").asString());
        assertTrue(sent.path("approved_findings").isArray());
        assertEquals("web", sent.path("requested_by").asString());

        // the event is stored just after the run is marked done
        JsonNode done = null;
        for (int i = 0; i < 50 && done == null; i++) {
            done = simulationDone(run.path("run_id").asString());
            if (done == null) {
                Thread.sleep(50);
            }
        }
        assertNotNull(done, "simulation_done event");
        assertEquals("sim_service", done.path("payload").path("engine_source").asString());
    }

    private JsonNode simulationDone(String runId) throws Exception {
        JsonNode events = jsonMapper.readTree(mvc.perform(get("/buildings/B01/events")).andReturn().getResponse().getContentAsString());
        JsonNode done = null;
        for (JsonNode e : events) {
            if ("simulation_done".equals(e.path("type").asString()) && runId.equals(e.path("payload").path("run_id").asString())) {
                done = e;
            }
        }
        return done;
    }

    @Test
    void fallsBackToTheJavaModelWhenTheServiceFails() throws Exception {
        STATUS.set(500);
        ANSWER.set("{\"detail\": \"boom\"}");
        JsonNode run = runAndWait();
        assertEquals("done", run.path("status").asString(), "the demo never breaks on the simulation");
        assertEquals("bucket_v0", run.path("result").path("engine").asString());
        JsonNode assumptions = run.path("result").path("assumptions");
        assertTrue(assumptions.get(assumptions.size() - 1).asString().contains("was not available"), "the result says the Java model ran");

        // an answer that is not a SimulationResult is not kept either
        STATUS.set(200);
        ANSWER.set("{\"hello\": 1}");
        assertEquals("bucket_v0", runAndWait().path("result").path("engine").asString());
    }
}
