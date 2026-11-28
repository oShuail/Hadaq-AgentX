package com.example.hadaq.Entity.Converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

// event payloads and other free-form objects: stored as JSON text, keys kept exactly as sent
@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(Map<String, Object> value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.readValue(column, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }
}
