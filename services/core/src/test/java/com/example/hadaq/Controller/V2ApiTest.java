package com.example.hadaq.Controller;

import com.example.hadaq.DTOIn.CaptureMetaDTOIn;
import com.example.hadaq.DTOIn.CaptureResultsDTOIn;
import com.example.hadaq.DTOIn.FindingDTOIn;
import com.example.hadaq.DTOIn.ReviewRequestDTOIn;
import com.example.hadaq.DTOIn.WorkOrderDTOIn;
import com.example.hadaq.Repository.CaptureRepository;
import com.example.hadaq.Repository.FindingRepository;
import com.example.hadaq.Repository.InspectionRepository;
import com.example.hadaq.Repository.SurveyRepository;
import com.example.hadaq.Repository.WorkOrderRepository;
import com.example.hadaq.Service.SurveyService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// contracts v2 end to end with the built-in mock AI worker, plus Omar's examples in contracts/v2/examples
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V2ApiTest {

    static final Path EXAMPLES = Path.of("../../contracts/v2/examples");

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    Validator validator;
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

    static JsonNode example(JsonMapper mapper, String name) throws Exception {
        return mapper.readTree(Files.readString(EXAMPLES.resolve(name + ".json")));
    }

    static byte[] wallPhoto() throws Exception {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 320; x++) {
            for (int y = 0; y < 240; y++) {
                image.setRGB(x, y, 0xB18B63);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    // the contract's capture_meta example with our own survey and client_uuid
    ObjectNode meta(String clientUuid) throws Exception {
        ObjectNode meta = (ObjectNode) example(jsonMapper, "capture_meta");
        meta.put("client_uuid", clientUuid);
        meta.put("survey_id", surveys.findAll().get(0).getSurveyId());
        return meta;
    }

    MvcResult upload(JsonNode meta) throws Exception {
        return mvc.perform(multipart("/api/v2/captures")
                        .file(new MockMultipartFile("file", "wall.jpg", "image/jpeg", wallPhoto()))
                        .file(new MockMultipartFile("meta", "", "application/json", jsonMapper.writeValueAsBytes(meta))))
                .andReturn();
    }

    JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    JsonNode waitFor(String captureId, String wanted) throws Exception {
        JsonNode capture = null;
        for (int i = 0; i < 100; i++) {
            capture = json(mvc.perform(get("/api/v2/captures/" + captureId)).andReturn());
            if (capture.path("status").asString().equals(wanted)) {
                return capture;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("capture never became " + wanted + ": " + capture);
    }

    @Test
    void examplesInContractsV2AreValidHere() throws Exception {
        assertEquals(Set.of(), validator.validate(jsonMapper.treeToValue(example(jsonMapper, "capture_meta"), CaptureMetaDTOIn.class)));
        assertEquals(Set.of(), validator.validate(jsonMapper.treeToValue(example(jsonMapper, "capture_results"), CaptureResultsDTOIn.class)));
        assertEquals(Set.of(), validator.validate(jsonMapper.treeToValue(example(jsonMapper, "finding"), FindingDTOIn.class)));
        assertEquals(Set.of(), validator.validate(jsonMapper.treeToValue(example(jsonMapper, "work_order"), WorkOrderDTOIn.class)));
        assertEquals(Set.of(), validator.validate(jsonMapper.treeToValue(example(jsonMapper, "review_request"), ReviewRequestDTOIn.class)));
        assertEquals(Set.of(), validator.validate(jsonMapper.treeToValue(example(jsonMapper, "survey_request"), com.example.hadaq.DTOIn.SurveyRequestDTOIn.class)));

        // our registry has the same fields as the contract's example
        JsonNode ours = json(mvc.perform(get("/api/v2/registry")).andExpect(status().isOk()).andReturn());
        JsonNode theirs = example(jsonMapper, "registry");
        assertEquals(names(theirs), names(ours));
        assertEquals(names(theirs.path("buildings").get(0)), names(ours.path("buildings").get(0)));
        assertEquals(names(theirs.path("buildings").get(0).path("walls").get(0)), names(ours.path("buildings").get(0).path("walls").get(0)));
        assertEquals("قصر سلوى", ours.path("buildings").get(0).path("name_ar").asString());
        assertEquals("Qasr Salwa", ours.path("buildings").get(0).path("name_en").asString());
        assertEquals(32.8, ours.path("buildings").get(0).path("walls").get(0).path("width_m").asDouble());
        assertEquals(names(example(jsonMapper, "survey")), names(json(mvc.perform(get("/api/v2/surveys")).andReturn()).get(0)));
    }

    static Set<String> names(JsonNode node) {
        Set<String> out = new HashSet<>();
        node.properties().forEach(e -> out.add(e.getKey()));
        return out;
    }

    @Test
    void captureReviewApproveAndSitePriority() throws Exception {
        mvc.perform(get("/api/v2/surveys").param("assignee", "surveyor"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].survey_id", startsWith("srv_")))
                .andExpect(jsonPath("$[0].tasks[0].status", is("not_started")));
        mvc.perform(get("/api/v2/site/priority"))
                .andExpect(jsonPath("$[0].worst_escalation").value((Object) null))
                .andExpect(jsonPath("$[0].last_survey_at").value((Object) null));

        // upload: 202, then the same client_uuid again is a duplicate of the first
        MvcResult first = upload(meta("6f1c2b9a-4e7d-4c21-9a0b-1f2e3d4c5b6a"));
        assertEquals(202, first.getResponse().getStatus());
        JsonNode accepted = json(first);
        String captureId = accepted.path("capture_id").asString();
        assertTrue(captureId.startsWith("cap_"));
        assertEquals("uploaded", accepted.path("status").asString());
        assertEquals(false, accepted.path("duplicate").asBoolean());
        JsonNode again = json(upload(meta("6f1c2b9a-4e7d-4c21-9a0b-1f2e3d4c5b6a")));
        assertEquals(captureId, again.path("capture_id").asString());
        assertEquals(true, again.path("duplicate").asBoolean());

        // the mock AI worker analyses it
        JsonNode capture = waitFor(captureId, "analyzed");
        assertEquals(6, capture.path("findings_count").asInt());
        assertEquals(true, capture.path("urgent").asBoolean());
        assertEquals(0, capture.path("faces_hidden_server").asInt(), "the phone hid faces, the core did not need to");
        String inspectionId = capture.path("inspection_id").asString();
        assertTrue(inspectionId.startsWith("ins_"));
        mvc.perform(get("/api/v2/captures/" + captureId + "/file")).andExpect(status().isOk());
        mvc.perform(get("/api/v2/captures/" + captureId + "/rectified")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/surveys"))
                .andExpect(jsonPath("$[0].tasks[0].done_shots[0]", is("full")))
                .andExpect(jsonPath("$[0].tasks[0].status", is("done")));

        JsonNode inspection = json(mvc.perform(get("/api/v2/inspections/" + inspectionId))
                .andExpect(jsonPath("$.status", is("open")))
                .andExpect(jsonPath("$.captures", hasSize(1)))
                .andExpect(jsonPath("$.findings", hasSize(6)))
                .andExpect(jsonPath("$.work_orders", hasSize(6)))
                .andExpect(jsonPath("$.findings[0].review.status", is("pending")))
                .andExpect(jsonPath("$.findings[0].provenance", is("synthetic")))
                .andExpect(jsonPath("$.work_orders[0].status", is("draft_needs_field_check")))
                .andReturn());
        mvc.perform(get("/api/v2/site/priority"))
                .andExpect(jsonPath("$[0].building_id", is("B01")))
                .andExpect(jsonPath("$[0].worst_escalation", is("urgent")))
                .andExpect(jsonPath("$[0].top_reason_ar", containsString("على مسار الزوار")));

        // only the conservator decides; nothing is approved while a finding is pending
        String approve = "/api/v2/inspections/" + inspectionId + "/approve";
        mvc.perform(post(approve)).andExpect(status().isForbidden());
        mvc.perform(post(approve).header("X-Hadaq-User", "surveyor")).andExpect(status().isForbidden());
        mvc.perform(post(approve).header("X-Hadaq-User", "admin")).andExpect(status().isBadRequest());
        mvc.perform(post(approve).header("X-Hadaq-User", "conservator"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("6 finding(s) still pending review")));

        String rejectedFinding = null;
        for (JsonNode f : inspection.path("findings")) {
            String id = f.path("finding_id").asString();
            String type = f.path("damage_type").asString();
            String body = switch (type) {
                case "voids_burrows" -> "{\"status\": \"rejected\", \"note\": \"a shadow, not holes\"}";
                case "salt_efflorescence" -> example(jsonMapper, "review_request").toString();
                default -> "{\"status\": \"accepted\"}";
            };
            if (type.equals("voids_burrows")) {
                rejectedFinding = id;
            }
            mvc.perform(patch("/api/v2/findings/" + id + "/review").header("X-Hadaq-User", "conservator")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.review.by", is("conservator")));
        }
        mvc.perform(patch("/api/v2/findings/" + rejectedFinding + "/review").header("X-Hadaq-User", "conservator")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"corrected\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("a correction needs corrected_type or corrected_severity")));

        JsonNode approved = json(mvc.perform(post(approve).header("X-Hadaq-User", "conservator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("approved")))
                .andExpect(jsonPath("$.approved_by", is("conservator")))
                .andReturn());
        int rejectedOrders = 0;
        for (JsonNode wo : approved.path("work_orders")) {
            boolean forRejected = wo.path("finding_ids").get(0).asString().equals(rejectedFinding);
            assertEquals(forRejected ? "rejected" : "approved", wo.path("status").asString());
            rejectedOrders += forRejected ? 1 : 0;
        }
        assertEquals(1, rejectedOrders);
        for (JsonNode f : approved.path("findings")) {
            if (f.path("damage_type").asString().equals("salt_efflorescence")) {
                assertEquals("corrected", f.path("review").path("status").asString());
                assertEquals(1, f.path("review").path("corrected_severity").asInt());
            }
        }
        mvc.perform(post(approve).header("X-Hadaq-User", "conservator")).andExpect(status().isConflict());
        mvc.perform(patch("/api/v2/findings/" + rejectedFinding + "/review").header("X-Hadaq-User", "conservator")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"accepted\"}")).andExpect(status().isConflict());

        // the rejected finding no longer counts as open
        mvc.perform(get("/api/v2/site/priority"))
                .andExpect(jsonPath("$[0].open_findings.urgent", is(1)))
                .andExpect(jsonPath("$[0].last_survey_at", is("2026-11-29T06:41:12Z")));

        // a new capture of the wall opens a new inspection, and the approved findings are its "previous"
        JsonNode next = json(upload(meta("0a0b0c0d-1111-2222-3333-444455556666")));
        JsonNode nextCapture = waitFor(next.path("capture_id").asString(), "analyzed");
        assertTrue(!nextCapture.path("inspection_id").asString().equals(inspectionId));
        mvc.perform(get("/api/v2/inspections/" + nextCapture.path("inspection_id").asString()))
                .andExpect(jsonPath("$.findings[?(@.damage_type == 'base_erosion')].change", org.hamcrest.Matchers.contains("unchanged")))
                .andExpect(jsonPath("$.findings[?(@.damage_type == 'voids_burrows')].change", org.hamcrest.Matchers.contains("new")));
        mvc.perform(get("/api/v2/buildings/B01/inspections")).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].inspection_id", is(inspectionId)));
        mvc.perform(get("/api/v2/buildings/B99/inspections")).andExpect(status().isNotFound());

        // every v2 id is evidence
        mvc.perform(get("/api/v2/evidence/" + captureId)).andExpect(jsonPath("$.kind", is("capture")));
        mvc.perform(get("/api/v2/evidence/" + inspectionId)).andExpect(jsonPath("$.kind", is("inspection")));
        mvc.perform(get("/api/v2/evidence/" + rejectedFinding)).andExpect(jsonPath("$.kind", is("finding")));
        mvc.perform(get("/api/v2/evidence/" + approved.path("work_orders").get(0).path("work_order_id").asString()))
                .andExpect(jsonPath("$.kind", is("work_order")));
        mvc.perform(get("/api/v2/evidence/" + surveys.findAll().get(0).getSurveyId())).andExpect(jsonPath("$.kind", is("survey")));
        mvc.perform(get("/api/v2/evidence/fnd_000000000000")).andExpect(status().isNotFound());
    }

    // the site manager plans a round; the surveyor sees it, and a partial shot leaves it partial
    @Test
    void siteManagerCreatesASurvey() throws Exception {
        String body = example(jsonMapper, "survey_request").toString();
        MvcResult created = mvc.perform(post("/api/v2/surveys").header("X-Hadaq-User", "site_manager")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.survey_id", startsWith("srv_")))
                .andExpect(jsonPath("$.title", is("Qasr Salwa, after the January rain")))
                .andExpect(jsonPath("$.due", is("2026-12-05T09:00:00Z")))
                .andExpect(jsonPath("$.tasks[0].required_shots", hasSize(2)))
                .andExpect(jsonPath("$.tasks[0].done_shots", hasSize(0)))
                .andExpect(jsonPath("$.tasks[0].status", is("not_started")))
                .andReturn();
        String surveyId = json(created).path("survey_id").asString();
        mvc.perform(get("/api/v2/surveys").param("assignee", "surveyor"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].survey_id", is(surveyId)));

        // one of the two shots arrives: partial
        ObjectNode meta = meta("survey-shot-c1-c4");
        meta.put("survey_id", surveyId);
        meta.put("segment", "C1-C4");
        waitFor(json(upload(meta)).path("capture_id").asString(), "analyzed");
        mvc.perform(get("/api/v2/surveys"))
                .andExpect(jsonPath("$[?(@.survey_id == '" + surveyId + "')].tasks[0].status", org.hamcrest.Matchers.contains("partial")))
                .andExpect(jsonPath("$[?(@.survey_id == '" + surveyId + "')].tasks[0].done_shots[0]", org.hamcrest.Matchers.contains("C1-C4")));

        // only the site manager, and only walls and columns that exist
        mvc.perform(post("/api/v2/surveys").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/surveys").header("X-Hadaq-User", "surveyor").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        String[][] bad = {
                {"{\"title\": \"x\", \"tasks\": []}", "tasks needs at least one wall"},
                {"{\"title\": \"\", \"tasks\": [{\"building_id\": \"B01\", \"wall_id\": \"W01\"}]}", "title is required"},
                {"{\"title\": \"x\", \"tasks\": [{\"building_id\": \"B01\", \"wall_id\": \"W09\"}]}", "Unknown wall W09 on building B01"},
                {"{\"title\": \"x\", \"tasks\": [{\"building_id\": \"B01\", \"wall_id\": \"W01\", \"required_shots\": [\"C7-C9\"]}]}", "Shot C7-C9 is outside wall W01 (columns C1 to C8)"},
                {"{\"title\": \"x\", \"tasks\": [{\"building_id\": \"B01\", \"wall_id\": \"W01\", \"required_shots\": [\"left side\"]}]}", "required_shots must be 'full' or a column range like 'C1-C2'"},
                {"{\"title\": \"x\", \"tasks\": [{\"building_id\": \"B01\", \"wall_id\": \"W01\"}, {\"building_id\": \"B01\", \"wall_id\": \"W01\"}]}", "Wall W01 of B01 is listed twice; put all its shots in one task"}};
        for (String[] c : bad) {
            mvc.perform(post("/api/v2/surveys").header("X-Hadaq-User", "site_manager").contentType(MediaType.APPLICATION_JSON).content(c[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is(c[1])));
        }
        mvc.perform(post("/api/v2/surveys").header("X-Hadaq-User", "site_manager").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"x\", \"tasks\": [{\"building_id\": \"B99\", \"wall_id\": \"W01\"}]}"))
                .andExpect(status().isNotFound());
        // a default task is one "full" shot, and the default assignee is the surveyor
        mvc.perform(post("/api/v2/surveys").header("X-Hadaq-User", "site_manager").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Quick round\", \"tasks\": [{\"building_id\": \"B01\", \"wall_id\": \"W01\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignee", is("surveyor")))
                .andExpect(jsonPath("$.due").value((Object) null))
                .andExpect(jsonPath("$.tasks[0].required_shots[0]", is("full")));
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    // two simultaneous retries from a phone must not both be stored (MariaDB 10.4 included)
    @Test
    void clientUuidIsUniqueInTheDatabase() {
        Integer unique = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'captures' AND column_name = 'client_uuid' AND non_unique = 0
                """, Integer.class);
        assertTrue(unique != null && unique > 0, "no unique index on captures.client_uuid");
    }

    @Test
    void badUploadsAreRefused() throws Exception {
        ObjectNode unknownWall = meta("1234567890ab");
        unknownWall.put("wall_id", "W09");
        assertEquals(400, upload(unknownWall).getResponse().getStatus());
        ObjectNode unknownBuilding = meta("1234567890ac");
        unknownBuilding.put("building_id", "B99");
        assertEquals(404, upload(unknownBuilding).getResponse().getStatus());
        ObjectNode shortUuid = meta("short");
        MvcResult r = upload(shortUuid);
        assertEquals(400, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("client_uuid"));
        ObjectNode corners = meta("1234567890ad");
        corners.putArray("corners_px").addArray().add(1).add(2);
        assertEquals(400, upload(corners).getResponse().getStatus());
        assertEquals(400, mvc.perform(multipart("/api/v2/captures")
                        .file(new MockMultipartFile("file", "wall.jpg", "image/jpeg", wallPhoto()))
                        .file(new MockMultipartFile("meta", "", "application/json", "{not json".getBytes())))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void facesAreHiddenWhenThePhoneDidNotCheck() throws Exception {
        ObjectNode meta = meta("faces-not-checked-01");
        meta.putNull("faces_hidden");
        JsonNode capture = waitFor(json(upload(meta)).path("capture_id").asString(), "analyzed");
        assertTrue(capture.path("faces_hidden").isNull());
        assertEquals(0, capture.path("faces_hidden_server").asInt(), "checked by the core, no faces on a plain wall");
    }

    @Test
    void contractExampleResultsReplaceAnAnalysis() throws Exception {
        String captureId = json(upload(meta("results-example-01"))).path("capture_id").asString();
        waitFor(captureId, "analyzed");
        ObjectNode results = (ObjectNode) example(jsonMapper, "capture_results");
        results.put("capture_id", captureId);
        ((ObjectNode) results.path("findings").get(0)).put("capture_id", captureId);

        mvc.perform(multipart("/api/v2/captures/" + captureId + "/results")
                        .file(new MockMultipartFile("results", "", "application/json", jsonMapper.writeValueAsBytes(results)))
                        .file(new MockMultipartFile("rectified", "rect.jpg", "image/jpeg", wallPhoto())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("analyzed")))
                .andExpect(jsonPath("$.findings_count", is(1)))
                .andExpect(jsonPath("$.scale", is("absolute")))
                .andExpect(jsonPath("$.px_per_m", is(500.0)))
                .andExpect(jsonPath("$.rectified_url", containsString("/api/v2/captures/" + captureId + "/rectified")));
        mvc.perform(get("/api/v2/captures/" + captureId + "/rectified")).andExpect(status().isOk());
        mvc.perform(get("/api/v2/evidence/fnd_1a2b3c4d5e6f"))
                .andExpect(jsonPath("$.value.lookalike.pair_id", is("dark_stain_base")))
                .andExpect(jsonPath("$.value.priority.escalation", is("urgent")))
                .andExpect(jsonPath("$.value.review.status", is("pending")));

        // wrong capture id, and a type that can only be suspected
        mvc.perform(multipart("/api/v2/captures/" + captureId + "/results")
                        .file(new MockMultipartFile("results", "", "application/json",
                                jsonMapper.writeValueAsBytes(results.put("capture_id", "cap_other000000")))))
                .andExpect(status().isBadRequest());
        results.put("capture_id", captureId);
        ((ObjectNode) results.path("findings").get(0)).put("damage_type", "structural_deformation");
        mvc.perform(multipart("/api/v2/captures/" + captureId + "/results")
                        .file(new MockMultipartFile("results", "", "application/json", jsonMapper.writeValueAsBytes(results))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("structural_deformation and incompatible_repair can only be reported as suspected")));
        mvc.perform(post("/api/v2/captures/" + captureId + "/retry")).andExpect(status().isConflict());
    }
}
