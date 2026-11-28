package com.example.hadaq.Entity.Converter;

import jakarta.persistence.AttributeConverter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// a typed value stored as JSON text in one column. Subclasses only name the type
public abstract class JsonColumnConverter<T> implements AttributeConverter<T, String> {

    protected static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final TypeReference<T> type;

    protected JsonColumnConverter(TypeReference<T> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(T value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    @Override
    public T convertToEntityAttribute(String column) {
        return column == null || column.isBlank() ? null : MAPPER.readValue(column, type);
    }
}
