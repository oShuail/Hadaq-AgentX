package com.example.hadaq.DTOIn;

import com.example.hadaq.Enum.Scenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// POST /simulations (web app "what if", agents' run_rain_simulation / compare_scenarios)
public record SimulationRequestDTOIn(
        @NotBlank(message = "building_id is required")
        String buildingId,

        @Size(max = 10, message = "wall_id is too long")
        String wallId,

        Scenario scenario,

        @Valid
        WeatherSpecDTOIn weather,

        @Size(max = 40, message = "requested_by is too long")
        String requestedBy
) {
    public SimulationRequestDTOIn {
        if (wallId == null || wallId.isBlank()) {
            wallId = "W01";
        }
        if (scenario == null) {
            scenario = Scenario.STORM_REPLAY;
        }
        if (weather == null) {
            weather = WeatherSpecDTOIn.defaults();
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            requestedBy = "user";
        }
    }

    public SimulationRequestDTOIn withWeather(WeatherSpecDTOIn newWeather) {
        return new SimulationRequestDTOIn(buildingId, wallId, scenario, newWeather, requestedBy);
    }
}
