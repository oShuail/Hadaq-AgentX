package com.example.hadaq.Model;

import com.example.hadaq.Enum.SensorStatus;

import java.util.List;

public record HealthResult(SensorStatus status, List<String> reasons) {
}
