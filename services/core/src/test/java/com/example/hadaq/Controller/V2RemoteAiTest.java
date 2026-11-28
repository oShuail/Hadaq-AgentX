package com.example.hadaq.Controller;

import com.example.hadaq.Repository.CaptureRepository;
import com.example.hadaq.Repository.FindingRepository;
import com.example.hadaq.Repository.InspectionRepository;
import com.example.hadaq.Repository.SurveyRepository;
import com.example.hadaq.Repository.WorkOrderRepository;
import com.example.hadaq.Service.SurveyService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// AI_MODE=remote against a stand-in AI worker: the ProcessRequest the core sends, the callback, failure and retry
@SpringBootTest(properties = "hadaq.ai.mode=remote")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V2RemoteAiTest {

    static final BlockingQueue<String> REQUESTS = new LinkedBlockingQueue<>();
    static final AtomicInteger ANSWER = new AtomicInteger(202);
    static HttpServer worker;

    @DynamicPropertySource
    static void aiWorker(DynamicPropertyRegistry registry) throws IOException {
        worker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        worker.createContext("/ai/process", exchange -> {
            // like a strict server: no Content-Length (a chunked body) = 411, so the core must send the length
            if (exchange.getRequestHeaders().getFirst("Content-Length") == null) {
                exchange.sendResponseHeaders(411, -1);
                exchange.close();
                return;
            }
            REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(ANSWER.get(), -1);
            exchange.close();
        });
        worker.start();
        registry.add("hadaq.ai.url", () -> "http://127.0.0.1:" + worker.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        worker.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    SurveyService surveyService;
    @Autowired
    SurveyRepository surveys;
    @Autowired
    InspectionRepository inspections;
    @Autowired
    CaptureRepository captures;
    @Autowired
    FindingRepository findings;
    @Autowired
    WorkOrderRepository workOrders;

    @BeforeEach
    void clean() {
        workOrders.deleteAll();
        findings.deleteAll();
        captures.deleteAll();
        inspections.deleteAll();
        surveys.deleteAll();
        surveyService.seed();
        REQUESTS.clear();
        ANSWER.set(202);
    }

    String upload(String clientUuid) throws Exception {
        ObjectNode meta = (ObjectNode) jsonMapper.readTree(Files.readString(V2ApiTest.EXAMPLES.resolve("capture_meta.json")));
        meta.put("client_uuid", clientUuid);
        meta.put("survey_id", surveys.findAll().get(0).getSurveyId());
        MvcResult result = mvc.perform(multipart("/api/v2/captures")
                        .file(new MockMultipartFile("file", "wall.jpg", "image/jpeg", V2ApiTest.wallPhoto()))
                        .file(new MockMultipartFile("meta", "", "application/json", jsonMapper.writeValueAsBytes(meta))))
                .andExpect(status().isAccepted()).andReturn();
        return jsonMapper.readTree(result.getResponse().getContentAsString()).path("capture_id").asString();
    }

    String statusOf(String captureId, String wanted) throws Exception {
        String now = null;
        for (int i = 0; i < 100; i++) {
            now = jsonMapper.readTree(mvc.perform(get("/api/v2/captures/" + captureId)).andReturn().getResponse().getContentAsString())
                    .path("status").asString();
            if (now.equals(wanted)) {
                return now;
            }
            Thread.sleep(50);
        }
        return now;
    }

    @Test
    void processRequestCallbackAndRetry() throws Exception {
        String captureId = upload("remote-ai-000001");
        String body = REQUESTS.poll(5, TimeUnit.SECONDS);
        assertNotNull(body, "the core API never called the AI worker");
        JsonNode request = jsonMapper.readTree(body);
        assertEquals(captureId, request.path("capture_id").asString());
        assertTrue(request.path("file_url").asString().endsWith("/api/v2/captures/" + captureId + "/file"));
        assertTrue(request.path("callback_url").asString().endsWith("/api/v2/captures/" + captureId + "/results"));
        assertEquals(32.8, request.path("wall").path("width_m").asDouble());
        assertEquals("assumed", request.path("wall").path("provenance").asString());
        assertEquals(4, request.path("corners_px").size());
        assertTrue(request.path("previous_findings").isArray());
        assertEquals("processing", statusOf(captureId, "processing"));

        // the worker calls back with the contract's example
        ObjectNode results = (ObjectNode) jsonMapper.readTree(Files.readString(V2ApiTest.EXAMPLES.resolve("capture_results.json")));
        results.put("capture_id", captureId);
        ((ObjectNode) results.path("findings").get(0)).put("capture_id", captureId);
        mvc.perform(multipart("/api/v2/captures/" + captureId + "/results")
                        .file(new MockMultipartFile("results", "", "application/json", jsonMapper.writeValueAsBytes(results))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("analyzed")))
                .andExpect(jsonPath("$.urgent", is(true)));

        // a second capture whose worker reuses the first one's finding id is refused, not overwritten
        String other = upload("remote-ai-000003");
        REQUESTS.poll(5, TimeUnit.SECONDS);
        results.put("capture_id", other);
        ((ObjectNode) results.path("findings").get(0)).put("capture_id", other);
        mvc.perform(multipart("/api/v2/captures/" + other + "/results")
                        .file(new MockMultipartFile("results", "", "application/json", jsonMapper.writeValueAsBytes(results))))
                .andExpect(status().isConflict());

        // the worker refuses: failed, then a retry goes out again
        ANSWER.set(500);
        String failing = upload("remote-ai-000002");
        assertEquals("failed", statusOf(failing, "failed"));
        mvc.perform(get("/api/v2/captures/" + failing)).andExpect(jsonPath("$.error", containsString("AI worker not reachable")));
        REQUESTS.clear();
        ANSWER.set(202);
        mvc.perform(post("/api/v2/captures/" + failing + "/retry")).andExpect(status().isOk());
        assertNotNull(REQUESTS.poll(5, TimeUnit.SECONDS), "retry did not reach the AI worker");
        assertEquals("processing", statusOf(failing, "processing"));

        // ok = false from the worker
        mvc.perform(multipart("/api/v2/captures/" + failing + "/results")
                        .file(new MockMultipartFile("results", "", "application/json",
                                ("{\"capture_id\": \"" + failing + "\", \"ok\": false, \"error\": \"photo too dark\", \"detector\": \"x\"}").getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("failed")))
                .andExpect(jsonPath("$.error", is("photo too dark")));
    }
}
