package com.example.hadaq.Service;

import com.example.hadaq.DTOIn.CaptureResultsDTOIn;
import com.example.hadaq.DTOIn.FindingDTOIn;
import com.example.hadaq.DTOIn.WorkOrderDTOIn;
import com.example.hadaq.DTOOut.V2Out;
import com.example.hadaq.Entity.Capture;
import com.example.hadaq.Enum.CitationStatus;
import com.example.hadaq.Enum.DamageType;
import com.example.hadaq.Enum.DetectionChange;
import com.example.hadaq.Enum.Escalation;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.Scale;
import com.example.hadaq.Enum.WorkOrderStatus;
import com.example.hadaq.Model.V2Values;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Numbers;
import com.example.hadaq.Util.ZoneIds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * Stand-in for the AI worker (POST /ai/process, Abdurhmn and Omar) until it exists: hadaq.ai.mode=mock.
 * It does not look at the photo. It reports the damage drawn on the demo wall (the same six as the v1 demo camera),
 * as v2 findings in wall metres, with a DGDA priority (weights ASSUMED), a lookalike question for the salts, and
 * one work order per finding taken from the team's treatment table. Everything is labelled synthetic.
 */
@Service
@RequiredArgsConstructor
public class MockAiWorker {

    public static final String MODEL = "mock-ai@v2";

    private static final Map<Integer, String> SEVERITY_AR = Map.of(1, "خفيف", 2, "متوسط", 3, "شديد");
    private static final Map<Integer, String> SEVERITY_EN = Map.of(1, "light", 2, "moderate", 3, "severe");
    private static final Set<DamageType> WATER = Set.of(DamageType.BASE_EROSION, DamageType.SALT_EFFLORESCENCE,
            DamageType.RAIN_GROOVES, DamageType.PLASTER_LOSS);

    private record Seed(DamageType type, int row, int col, int severity, double confidence) {
    }

    // the demo wall's damage (DemoFrames.STATIC_DAMAGE plus the R2C5 crack)
    private static final List<Seed> SEEDS = List.of(
            new Seed(DamageType.BASE_EROSION, 1, 4, 3, 0.86),
            new Seed(DamageType.SALT_EFFLORESCENCE, 1, 7, 2, 0.69),
            new Seed(DamageType.CRACK, 2, 5, 1, 0.83),
            new Seed(DamageType.VOIDS_BURROWS, 3, 2, 1, 0.66),
            new Seed(DamageType.RAIN_GROOVES, 3, 6, 2, 0.74),
            new Seed(DamageType.PARAPET_LOSS, 4, 7, 2, 0.78));

    private final V2RegistryService v2RegistryService;
    private final RegistryService registryService;
    private final TreatmentLibrary treatmentLibrary;

    public CaptureResultsDTOIn process(Capture capture, List<V2Out.Finding> previous) {
        V2Out.Wall wall = v2RegistryService.wall(capture.getBuildingId(), capture.getWallId());
        double cellW = wall.widthM() / wall.cols();
        double cellH = wall.heightM() / wall.rows();
        Map<String, Double> weights = registryService.valueWeightOverrides();
        double maxWeight = weights.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);

        List<FindingDTOIn> findings = new ArrayList<>();
        List<WorkOrderDTOIn> workOrders = new ArrayList<>();
        int urgent = 0;
        for (Seed seed : SEEDS) {
            String zone = ZoneIds.zoneId(capture.getBuildingId(), wall.id(), seed.row(), seed.col());
            double u0 = (seed.col() - 1) * cellW, v0 = (seed.row() - 1) * cellH;
            List<Double> box = List.of(round(u0 + 0.25 * cellW), round(seed.row() == 1 ? 0.0 : v0 + 0.2 * cellH),
                    round(u0 + 0.75 * cellW), round(v0 + 0.7 * cellH));
            double area = Numbers.round((box.get(2) - box.get(0)) * (box.get(3) - box.get(1)), 2);

            V2Out.Finding before = previous.stream()
                    .filter(p -> p.zoneId().equals(zone) && effectiveType(p) == seed.type()).findFirst().orElse(null);
            DetectionChange change = before == null ? DetectionChange.NEW : DetectionChange.UNCHANGED;

            V2Values.Priority priority = priority(seed, area, wall, weights.getOrDefault(zone, registryService.valueWeightDefault()) / maxWeight);
            if (priority.escalation() == Escalation.URGENT) {
                urgent++;
            }
            V2Values.Lookalike lookalike = seed.type() == DamageType.SALT_EFFLORESCENCE
                    ? new V2Values.Lookalike("salt_or_growth", List.of("salt efflorescence", "biological growth"),
                    List.of("touch test: powdery salt or soft growth", "is it fed by moisture at the base"),
                    "البقعة الفاتحة قد تكون تزهر أملاح أو نمواً حيوياً، يلزم فحص باللمس")
                    : null;
            String findingId = Ids.newId("fnd");
            findings.add(new FindingDTOIn(findingId, capture.getCaptureId(), capture.getInspectionId(),
                    capture.getBuildingId(), wall.id(), zone, seed.type(), seed.severity(), seed.confidence(), false,
                    null, box, null,
                    seed.type() == DamageType.CRACK ? round((box.get(3) - box.get(1)) * 100) : null,
                    seed.type() == DamageType.CRACK ? 1.2 : null,
                    area, change, before == null ? null : before.findingId(), lookalike, priority,
                    V2Values.Review.pending(), MODEL, Provenance.SYNTHETIC, List.of(capture.getCaptureId())));
            workOrder(findingId, seed, zone, box, priority.escalation(), capture).ifPresent(workOrders::add);
        }
        String summaryAr = "نتيجة محاكاة (عامل ذكاء اصطناعي تجريبي): " + findings.size() + " أضرار، منها " + urgent + " عاجل. تحتاج مراجعة مختص الترميم.";
        String summaryEn = "Simulated result (mock AI worker): " + findings.size() + " findings, " + urgent
                + " urgent. Real detection needs the AI worker (AI_MODE=remote). A conservator must review them.";
        return new CaptureResultsDTOIn(capture.getCaptureId(), true, null, Scale.RELATIVE, null, 0, findings,
                workOrders, summaryAr, summaryEn, MODEL, "core.mock_ai_worker@v2");
    }

    // DGDA criteria in their order; the weights are ours (ASSUMED)
    private V2Values.Priority priority(Seed seed, double areaM2, V2Out.Wall wall, double value) {
        boolean water = WATER.contains(seed.type());
        double safety = seed.severity() == 3 ? 1.0 : seed.type() == DamageType.PARAPET_LOSS ? 0.6 : 0.2;
        double active = water ? 0.8 : 0.4;
        double waterBeforeRain = water ? 1.0 : 0.0;
        double severityXArea = Numbers.round(seed.severity() / 3.0 * Math.min(1.0, areaM2 / 1.5), 2);
        double location = wall.visitorRoute() ? (seed.row() <= 2 ? 1.0 : 0.6) : 0.3;
        double score = Numbers.round(100 * (0.30 * safety + 0.15 * active + 0.20 * waterBeforeRain
                + 0.15 * severityXArea + 0.10 * Math.min(1.0, value) + 0.10 * location), 1);
        Escalation escalation = seed.severity() == 3 && location >= 1.0 ? Escalation.URGENT
                : score >= 75 ? Escalation.URGENT : score >= 55 ? Escalation.HIGH : score >= 35 ? Escalation.MEDIUM : Escalation.LOW;
        String where = "R" + seed.row() + "C" + seed.col();
        String nameAr = treatmentLibrary.treatment(seed.type().value()).map(t -> t.path("name_ar").asString()).orElse(seed.type().value());
        String nameEn = treatmentLibrary.treatment(seed.type().value()).map(t -> t.path("name_en").asString()).orElse(seed.type().value());
        boolean onRoute = wall.visitorRoute() && seed.row() <= 2;
        String reasonAr = nameAr + " " + SEVERITY_AR.get(seed.severity()) + " في " + where + (onRoute ? " على مسار الزوار" : "");
        String reasonEn = capitalize(SEVERITY_EN.get(seed.severity())) + " " + nameEn.toLowerCase() + " at " + where + (onRoute ? " on a visitor route" : "");
        return new V2Values.Priority(score, new V2Values.PriorityCriteria(safety, active, waterBeforeRain, severityXArea,
                Numbers.round(Math.min(1.0, value), 2), location), escalation, reasonAr, reasonEn,
                "DGDA criteria, weights ASSUMED (core mock AI worker)");
    }

    // one work order per finding, from the team's treatment table; nothing added of our own
    private java.util.Optional<WorkOrderDTOIn> workOrder(String findingId, Seed seed, String zone, List<Double> box,
                                                         Escalation escalation, Capture capture) {
        return treatmentLibrary.treatment(seed.type().value()).map(t -> {
            List<String> stepsAr = new ArrayList<>();
            List<String> stepsEn = new ArrayList<>();
            add(stepsAr, t.path("first_step_ar"));
            add(stepsEn, t.path("first_step_en"));
            JsonNode bySeverity = t.path("steps_by_severity").path(seed.severity() == 1 ? "fine" : seed.severity() == 2 ? "wide" : "structural");
            if (!bySeverity.isMissingNode()) {
                add(stepsAr, bySeverity.path("ar"));
                add(stepsEn, bySeverity.path("en"));
            } else {
                add(stepsAr, t.path("treatment_ar"));
                add(stepsEn, t.path("treatment_en"));
            }
            String source = t.path("source").asString("team_proposal");
            CitationStatus status = "heritage_authority".equals(source) ? CitationStatus.APPROVED_LIBRARY : CitationStatus.TEAM_PROPOSAL;
            return new WorkOrderDTOIn(Ids.newId("wo"), capture.getInspectionId(), List.of(findingId),
                    new V2Values.WorkOrderLocation(capture.getBuildingId(), capture.getWallId(), List.of(zone), box),
                    seed.type().value(), stepsAr, stepsEn, strings(t.path("materials_ar")), strings(t.path("materials_en")),
                    strings(t.path("tools_ar")), strings(t.path("tools_en")), t.path("fix_moisture_source_first").asBoolean(false),
                    new V2Values.Citation(treatmentLibrary.sourceLabel(source), null, status), escalation,
                    WorkOrderStatus.DRAFT_NEEDS_FIELD_CHECK, null);
        });
    }

    private static DamageType effectiveType(V2Out.Finding f) {
        return f.review() != null && f.review().correctedType() != null ? f.review().correctedType() : f.damageType();
    }

    private static void add(List<String> list, JsonNode text) {
        String value = text.isMissingNode() || text.isNull() ? null : text.asString();
        if (value != null && !value.isBlank() && !list.contains(value)) {
            list.add(value);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static double round(double value) {
        return Numbers.round(value, 2);
    }

    private static String capitalize(String text) {
        return text.substring(0, 1).toUpperCase() + text.substring(1);
    }
}
