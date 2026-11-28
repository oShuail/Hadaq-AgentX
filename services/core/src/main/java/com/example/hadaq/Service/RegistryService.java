package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.Enum.SensorKind;
import com.example.hadaq.Model.SensorSpec;
import com.example.hadaq.Model.WallSpec;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// the building registry (contracts/building_b01.json): walls, zones, sensors, cameras. Read once at startup
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistryService {

    private final JsonMapper jsonMapper;

    @Value("${hadaq.registry-path:}")
    private String registryPath;

    @Getter
    private JsonNode registry;
    private List<SensorSpec> sensors = List.of();
    private List<WallSpec> walls = List.of();

    @PostConstruct
    void load() throws IOException {
        if (registryPath != null && !registryPath.isBlank()) {
            try (InputStream in = Files.newInputStream(Path.of(registryPath))) {
                registry = jsonMapper.readTree(in);
            }
            log.info("Registry loaded from {}", registryPath);
        } else {
            try (InputStream in = new ClassPathResource("contracts/building_b01.json").getInputStream()) {
                registry = jsonMapper.readTree(in);
            }
            log.info("Registry loaded from the classpath (contracts/building_b01.json)");
        }

        List<SensorSpec> sensorList = new ArrayList<>();
        for (JsonNode s : registry.path("sensors")) {
            sensorList.add(new SensorSpec(text(s, "id"), SensorKind.fromValue(text(s, "kind")), text(s, "unit"),
                    text(s, "zone_id"), text(s, "device_id"), text(s, "source"), text(s, "label")));
        }
        sensors = List.copyOf(sensorList);

        List<WallSpec> wallList = new ArrayList<>();
        for (JsonNode w : registry.path("walls")) {
            wallList.add(new WallSpec(text(w, "id"), text(w, "building_id"), w.path("azimuth_deg").asDouble(270),
                    w.path("rows").asInt(4), w.path("cols").asInt(8)));
        }
        walls = List.copyOf(wallList);
    }

    public String buildingId() {
        return registry.path("building").path("id").asString();
    }

    // 404 for any other building (only B01 has a registry for now)
    public JsonNode registryFor(String buildingId) {
        if (!buildingId().equals(buildingId)) {
            throw ApiException.notFound("Unknown building " + buildingId);
        }
        return registry;
    }

    public void check(String buildingId) {
        registryFor(buildingId);
    }

    public List<SensorSpec> sensors() {
        return sensors;
    }

    public Optional<SensorSpec> sensor(String sensorId) {
        return sensors.stream().filter(s -> s.id().equals(sensorId)).findFirst();
    }

    public List<SensorSpec> sensorsOfKind(SensorKind kind) {
        return sensors.stream().filter(s -> s.kind() == kind).toList();
    }

    public Optional<WallSpec> wall(String wallId) {
        return walls.stream().filter(w -> w.id().equals(wallId)).findFirst();
    }

    // [x0, y0, x1, y1] of the wall face inside this camera's photos, and the wall it looks at
    public Optional<CameraSpec> camera(String cameraId) {
        for (JsonNode c : registry.path("cameras")) {
            if (cameraId.equals(text(c, "id"))) {
                JsonNode box = c.path("wall_bbox_px");
                double[] bbox = {box.path(0).asDouble(), box.path(1).asDouble(), box.path(2).asDouble(), box.path(3).asDouble()};
                return Optional.of(new CameraSpec(cameraId, text(c, "wall_id"), bbox));
            }
        }
        return Optional.empty();
    }

    public record CameraSpec(String id, String wallId, double[] wallBboxPx) {
    }

    public double siteLat() {
        return registry.path("site").path("center").path("lat").asDouble();
    }

    public double siteLon() {
        return registry.path("site").path("center").path("lon").asDouble();
    }

    public double heavyRainDaysPerYear() {
        return registry.path("site").path("heavy_rain_days_per_year").asDouble();
    }

    // the real past storm Basma picked (null if not set)
    public String demoStormDate() {
        JsonNode date = registry.path("site").path("demo_storm_date");
        return date.isMissingNode() || date.isNull() ? null : date.asString();
    }

    // ABC C score: relative heritage value of each zone (ASSUMED for the demo)
    public Map<String, Double> valueWeightOverrides() {
        Map<String, Double> weights = new LinkedHashMap<>();
        JsonNode overrides = registry.path("zones").path("value_weight_overrides");
        for (Map.Entry<String, JsonNode> entry : overrides.properties()) {
            weights.put(entry.getKey(), entry.getValue().asDouble());
        }
        return weights;
    }

    public double valueWeightDefault() {
        return registry.path("zones").path("value_weight_default").asDouble(3);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
