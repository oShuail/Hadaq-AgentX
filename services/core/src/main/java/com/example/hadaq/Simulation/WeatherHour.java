package com.example.hadaq.Simulation;

import java.time.OffsetDateTime;

// one hour of weather. windDirDeg = where the wind comes FROM, degrees from north. rh = relative humidity %
// JSON (snake_case): {"time", "rain_mm", "wind_ms", "wind_dir_deg", "temp_c", "rh"}, the same file format as the Python cache
public record WeatherHour(OffsetDateTime time, double rainMm, double windMs, double windDirDeg, double tempC, double rh) {

    public WeatherHour withRain(double newRainMm) {
        return new WeatherHour(time, newRainMm, windMs, windDirDeg, tempC, rh);
    }
}
