package com.example.hadaq.DTOIn;

import com.example.hadaq.Enum.WeatherMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

// the weather a simulation runs on. Defaults = the contract defaults (SimulationRequest.weather)
public record WeatherSpecDTOIn(
        WeatherMode mode,

        // YYYY-MM-DD, used by replay (empty = the registry's demo_storm_date)
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "date must be YYYY-MM-DD")
        String date,

        @Min(value = 6, message = "hours must be between 6 and 336")
        @Max(value = 336, message = "hours must be between 6 and 336")
        Integer hours,

        Double peakRainMmH,
        Double windMs,
        Double windDirDeg,
        Double rainMultiplier
) {
    public WeatherSpecDTOIn {
        if (mode == null) {
            mode = WeatherMode.SYNTHETIC_STORM;
        }
        if (hours == null) {
            hours = 72;
        }
        if (peakRainMmH == null) {
            peakRainMmH = 12.0;
        }
        if (windMs == null) {
            windMs = 8.0;
        }
        if (windDirDeg == null) {
            windDirDeg = 270.0;
        }
        if (rainMultiplier == null) {
            rainMultiplier = 1.0;
        }
    }

    public static WeatherSpecDTOIn defaults() {
        return new WeatherSpecDTOIn(null, null, null, null, null, null, null);
    }

    public WeatherSpecDTOIn withModeAndDate(WeatherMode newMode, String newDate) {
        return new WeatherSpecDTOIn(newMode, newDate, hours, peakRainMmH, windMs, windDirDeg, rainMultiplier);
    }
}
