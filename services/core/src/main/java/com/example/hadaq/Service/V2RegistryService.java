package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.DTOOut.V2Out;
import com.example.hadaq.Enum.OriginalFabric;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Model.V2Values;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// GET /api/v2/registry: the buildings and walls of contracts/building_b01.json in the v2 shape
@Service
@RequiredArgsConstructor
public class V2RegistryService {

    // "Qasr Salwa (قصر سلوى)" -> English, Arabic
    private static final Pattern NAME = Pattern.compile("^(.*?)\\s*\\((.*)\\)\\s*$");

    private final RegistryService registryService;

    public V2Out.Registry registry() {
        JsonNode reg = registryService.getRegistry();
        return new V2Out.Registry(reg.path("site").path("id").asString("diriyah"), List.of(building()));
    }

    public V2Out.Building building() {
        JsonNode b = registryService.getRegistry().path("building");
        String name = b.path("name").asString("");
        Matcher m = NAME.matcher(name);
        String nameEn = m.matches() ? m.group(1) : name;
        String nameAr = m.matches() ? m.group(2) : name;
        List<V2Out.Wall> walls = new ArrayList<>();
        for (JsonNode w : registryService.getRegistry().path("walls")) {
            walls.add(wall(w));
        }
        // the focus building has a 3D model in the web app
        return new V2Out.Building(b.path("id").asString(), nameAr, nameEn, b.path("lon").asDouble(), b.path("lat").asDouble(), true, walls);
    }

    public V2Out.Wall wall(String buildingId, String wallId) {
        registryService.check(buildingId);
        for (JsonNode w : registryService.getRegistry().path("walls")) {
            if (wallId.equals(w.path("id").asString())) {
                return wall(w);
            }
        }
        throw new ApiException("Unknown wall " + wallId + " on building " + buildingId);
    }

    private static V2Out.Wall wall(JsonNode w) {
        JsonNode line = w.path("base_line");
        V2Values.BaseLine baseLine = new V2Values.BaseLine(
                new V2Values.LonLat(line.path("start").path("lon").asDouble(), line.path("start").path("lat").asDouble()),
                new V2Values.LonLat(line.path("end").path("lon").asDouble(), line.path("end").path("lat").asDouble()));
        String fabric = w.path("original_fabric").asString("unknown");
        return new V2Out.Wall(w.path("id").asString(), w.path("building_id").asString(), w.path("name").asString(),
                w.path("azimuth_deg").asDouble(), w.path("width_m").asDouble(), w.path("height_m").asDouble(),
                w.path("rows").asInt(4), w.path("cols").asInt(8), baseLine,
                w.path("main_facade").asBoolean(false), w.path("visitor_route").asBoolean(false),
                OriginalFabric.fromValue(fabric.isBlank() ? "unknown" : fabric), Provenance.ASSUMED);
    }
}
