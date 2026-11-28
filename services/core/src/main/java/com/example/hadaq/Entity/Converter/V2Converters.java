package com.example.hadaq.Entity.Converter;

import com.example.hadaq.Model.V2Values;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

import java.util.List;

// the JSON columns of the v2 entities
public final class V2Converters {

    private V2Converters() {
    }

    @Converter
    public static class StringList extends JsonColumnConverter<List<String>> {
        public StringList() {
            super(new TypeReference<>() {
            });
        }
    }

    @Converter
    public static class GeoFix extends JsonColumnConverter<V2Values.GeoFix> {
        public GeoFix() {
            super(new TypeReference<>() {
            });
        }
    }

    @Converter
    public static class CaptureQuality extends JsonColumnConverter<V2Values.CaptureQuality> {
        public CaptureQuality() {
            super(new TypeReference<>() {
            });
        }
    }

    @Converter
    public static class Lookalike extends JsonColumnConverter<V2Values.Lookalike> {
        public Lookalike() {
            super(new TypeReference<>() {
            });
        }
    }

    @Converter
    public static class Priority extends JsonColumnConverter<V2Values.Priority> {
        public Priority() {
            super(new TypeReference<>() {
            });
        }
    }

    @Converter
    public static class Citation extends JsonColumnConverter<V2Values.Citation> {
        public Citation() {
            super(new TypeReference<>() {
            });
        }
    }

    @Converter
    public static class Location extends JsonColumnConverter<V2Values.WorkOrderLocation> {
        public Location() {
            super(new TypeReference<>() {
            });
        }
    }

    @Converter
    public static class SurveyTasks extends JsonColumnConverter<List<V2Values.SurveyTask>> {
        public SurveyTasks() {
            super(new TypeReference<>() {
            });
        }
    }
}
