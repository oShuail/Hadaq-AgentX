package com.example.hadaq.Entity.Converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

// a detection outline: [[x, y], [x, y], ...]
@Converter
public class PolygonConverter implements AttributeConverter<List<List<Double>>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(List<List<Double>> value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    @Override
    public List<List<Double>> convertToEntityAttribute(String column) {
        return column == null || column.isBlank() ? null : MAPPER.readValue(column, new TypeReference<List<List<Double>>>() {
        });
    }
}
