package com.example.hadaq.Controller;

import com.example.hadaq.Repository.CaptureRepository;
import com.example.hadaq.Repository.FindingRepository;
import com.example.hadaq.Repository.InspectionRepository;
import com.example.hadaq.Repository.SurveyRepository;
import com.example.hadaq.Repository.WorkOrderRepository;
import com.example.hadaq.Service.SurveyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// T4: POST /api/v2/demo/reset with demo mode on (its fake sensors, camera and vision off). Run twice: clean both times
@SpringBootTest(properties = {"hadaq.demo.enabled=true", "hadaq.demo.sensors=false", "hadaq.demo.camera=false",
        "hadaq.demo.vision=false", "hadaq.demo.seed-simulation=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoResetTest {

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
    }

    @Test
    void resetTwiceLeavesACleanDemo() throws Exception {
        // a demo run: one photo, analysed, the survey shot marked
        ObjectNode meta = (ObjectNode) jsonMapper.readTree(Files.readString(V2ApiTest.EXAMPLES.resolve("capture_meta.json")));
        meta.put("client_uuid", "demo-reset-0001");
        meta.put("survey_id", surveys.findAll().get(0).getSurveyId());
        String captureId = jsonMapper.readTree(mvc.perform(multipart("/api/v2/captures")
                        .file(new MockMultipartFile("file", "wall.jpg", "image/jpeg", V2ApiTest.wallPhoto()))
                        .file(new MockMultipartFile("meta", "", "application/json", jsonMapper.writeValueAsBytes(meta))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("capture_id").asString();
        for (int i = 0; i < 100 && !"analyzed".equals(captureStatus(captureId)); i++) {
            Thread.sleep(50);
        }
        mvc.perform(get("/api/v2/site/priority")).andExpect(jsonPath("$[0].worst_escalation", is("urgent")));
        mvc.perform(get("/api/v2/surveys")).andExpect(jsonPath("$[0].tasks[0].status", is("done")));

        // first reset: everything goes, the survey comes back with no shots
        mvc.perform(post("/api/v2/demo/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset", is(true)))
                .andExpect(jsonPath("$.cleared.captures", is(1)))
                .andExpect(jsonPath("$.cleared.findings", is(6)))
                .andExpect(jsonPath("$.cleared.work_orders", is(6)))
                .andExpect(jsonPath("$.cleared.files", is(1)))
                .andExpect(jsonPath("$.surveys", hasSize(1)))
                .andExpect(jsonPath("$.surveys[0].tasks[0].status", is("not_started")))
                .andExpect(jsonPath("$.surveys[0].tasks[0].done_shots", hasSize(0)));
        mvc.perform(get("/api/v2/captures/" + captureId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/site/priority"))
                .andExpect(jsonPath("$[0].worst_escalation").value((Object) null))
                .andExpect(jsonPath("$[0].last_survey_at").value((Object) null));
        mvc.perform(get("/api/v2/buildings/B01/inspections")).andExpect(jsonPath("$", hasSize(0)));

        // second reset in a row: still clean, still exactly one survey
        mvc.perform(post("/api/v2/demo/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared.captures", is(0)))
                .andExpect(jsonPath("$.cleared.surveys", is(1)))
                .andExpect(jsonPath("$.surveys", hasSize(1)));
        assertEquals(1, surveys.count());
    }

    private String captureStatus(String captureId) throws Exception {
        JsonNode capture = jsonMapper.readTree(mvc.perform(get("/api/v2/captures/" + captureId)).andReturn().getResponse().getContentAsString());
        return capture.path("status").asString();
    }
}
