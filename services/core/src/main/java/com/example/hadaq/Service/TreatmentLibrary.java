package com.example.hadaq.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.Optional;

/*
 * The team's treatment table (services/agents/hadaq_agents/treatments.json, packed into the jar at build time):
 * 11 damage types in Arabic and English with treatment, materials, tools and the source. Read only: the core API
 * never adds treatments, materials or tools of its own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreatmentLibrary {

    private final JsonMapper jsonMapper;
    private JsonNode table;

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource("treatments/treatments.json").getInputStream()) {
            table = jsonMapper.readTree(in);
        } catch (Exception exception) {
            log.warn("Treatment table not found ({}); work orders cannot be drafted", exception.getMessage());
            table = jsonMapper.createObjectNode();
        }
    }

    public Optional<JsonNode> treatment(String damageType) {
        for (JsonNode t : table.path("damage_types")) {
            if (damageType.equals(t.path("id").asString())) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    // e.g. "Heritage authority guidance (الهيئة)"
    public String sourceLabel(String sourceId) {
        return table.path("sources").path(sourceId).path("label_en").asString(sourceId);
    }
}
