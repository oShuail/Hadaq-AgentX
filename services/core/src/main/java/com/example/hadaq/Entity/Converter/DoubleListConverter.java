package com.example.hadaq.Entity.Converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

// bbox_xyxy: [x0, y0, x1, y1]
@Converter
public class DoubleListConverter implements AttributeConverter<List<Double>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(List<Double> value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    @Override
    public List<Double> convertToEntityAttribute(String column) {
        return column == null || column.isBlank() ? null : MAPPER.readValue(column, new TypeReference<List<Double>>() {
        });
    }
}
