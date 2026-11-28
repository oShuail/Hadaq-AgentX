package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.DTOIn.WeatherSpecDTOIn;
import com.example.hadaq.Enum.WeatherMode;
import com.example.hadaq.Simulation.WeatherHour;
import com.example.hadaq.Util.Numbers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/*
 * Weather for the simulation (port of hadaq_sim.weather, owner: Basma):
 * - replay: real hourly history from the Open-Meteo archive (ERA5 reanalysis), kept in a JSON cache for offline demos
 * - synthetic_storm: a bell-shaped rain burst, for tests and offline demos
 */
@Slf4j
@Service
public class WeatherService {

    public record WeatherResult(List<WeatherHour> hours, String source) {
    }

    private static final String OPEN_METEO_ARCHIVE = "https://archive-api.open-meteo.com/v1/archive";
    private static final String HOURLY_VARS = "temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m,wind_direction_10m";
    private static final ZoneOffset RIYADH = ZoneOffset.ofHours(3);

    private final JsonMapper jsonMapper;
    private final RestClient restClient;
    private final Path cacheDir;

    public WeatherService(JsonMapper jsonMapper, RestClient.Builder builder,
                          @Value("${hadaq.weather-cache-dir:./data/weather_cache}") String cacheDir) {
        this.jsonMapper = jsonMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.restClient = builder.clone().requestFactory(factory).build();
        this.cacheDir = Path.of(cacheDir);
    }

    public WeatherResult forSpec(WeatherSpecDTOIn spec, double lat, double lon) {
        List<WeatherHour> hours;
        String source;
        if (spec.mode() == WeatherMode.REPLAY) {
            if (spec.date() == null || spec.date().isBlank()) {
                throw new ApiException("weather.date is required for replay");
            }
            LocalDate day = LocalDate.parse(spec.date());
            int days = Math.max(1, (int) Math.ceil(spec.hours() / 24.0));
            List<WeatherHour> fetched = fetchHourlyCached(lat, lon, day, day.plusDays(days - 1));
            hours = new ArrayList<>(fetched.subList(0, Math.min(spec.hours(), fetched.size())));
            source = "Open-Meteo archive (ERA5), " + spec.date() + ", " + lat + "," + lon;
        } else {
            hours = syntheticStorm(null, spec.hours(), spec.peakRainMmH(), spec.windMs(), spec.windDirDeg(), 10.0, 18.0);
            source = String.format(Locale.ROOT, "synthetic storm, peak %s mm/h, wind %s m/s from %s deg",
                    spec.peakRainMmH(), spec.windMs(), spec.windDirDeg());
        }
        if (spec.rainMultiplier() != 1.0) {
            double multiplier = spec.rainMultiplier();
            hours = hours.stream().map(h -> h.withRain(h.rainMm() * multiplier)).toList();
            source += ", rain x" + spec.rainMultiplier();
        }
        return new WeatherResult(hours, source);
    }

    // bell-shaped rain burst starting at hour 6, then clear drying weather
    public static List<WeatherHour> syntheticStorm(OffsetDateTime start, int hours, double peakMmH, double windMs,
                                                   double windDirDeg, double rainHours, double tempC) {
        OffsetDateTime begin = start != null ? start : OffsetDateTime.now(ZoneId.of("Asia/Riyadh")).truncatedTo(ChronoUnit.HOURS);
        double center = 6 + rainHours / 2;
        double width = rainHours / 4;
        List<WeatherHour> out = new ArrayList<>();
        for (int h = 0; h < hours; h++) {
            double rain = peakMmH * Math.exp(-0.5 * Math.pow((h - center) / width, 2));
            rain = rain > 0.05 ? rain : 0.0;
            double daily = Math.sin(2 * Math.PI * (((begin.getHour() + h) % 24) - 9) / 24);
            boolean raining = rain > 0;
            out.add(new WeatherHour(
                    begin.plusHours(h),
                    Numbers.round(rain, 2),
                    raining ? windMs : Math.max(2.0, windMs * 0.4),
                    windDirDeg,
                    Numbers.round(tempC + (raining ? 2 : 7) * daily, 1),
                    Numbers.round(raining ? 85.0 : Math.max(15.0, 40.0 - 15 * daily), 1)));
        }
        return out;
    }

    // same as fetchHourly, but keeps a JSON copy so the demo works offline after the first run
    // (same file name and format as the Python cache, so both versions can share data/weather_cache)
    List<WeatherHour> fetchHourlyCached(double lat, double lon, LocalDate start, LocalDate end) {
        Path path = cacheDir.resolve(String.format(Locale.ROOT, "%.4f_%.4f_%s_%s.json", lat, lon, start, end));
        try {
            if (Files.exists(path)) {
                return jsonMapper.readValue(Files.readString(path), new TypeReference<List<WeatherHour>>() {
                });
            }
            List<WeatherHour> hours = fetchHourly(lat, lon, start, end);
            Files.createDirectories(path.getParent());
            Files.writeString(path, jsonMapper.writeValueAsString(hours));
            return hours;
        } catch (IOException exception) {
            throw new IllegalStateException("Weather cache error: " + exception.getMessage(), exception);
        }
    }

    // hourly history from Open-Meteo (free, no API key). Times are Riyadh local (+03:00)
    List<WeatherHour> fetchHourly(double lat, double lon, LocalDate start, LocalDate end) {
        log.info("Fetching Open-Meteo archive {} to {}", start, end);
        JsonNode body = restClient.get()
                .uri(OPEN_METEO_ARCHIVE + "?latitude={lat}&longitude={lon}&start_date={start}&end_date={end}"
                                + "&hourly={vars}&wind_speed_unit=ms&timezone=Asia/Riyadh",
                        lat, lon, start, end, HOURLY_VARS)
                .retrieve()
                .body(JsonNode.class);
        if (body == null) {
            throw new IllegalStateException("Open-Meteo returned an empty response");
        }
        JsonNode hourly = body.path("hourly");
        List<WeatherHour> hours = new ArrayList<>();
        JsonNode times = hourly.path("time");
        for (int i = 0; i < times.size(); i++) {
            hours.add(new WeatherHour(
                    LocalDateTime.parse(times.get(i).asString()).atOffset(RIYADH),
                    value(hourly, "precipitation", i, 0.0),
                    value(hourly, "wind_speed_10m", i, 0.0),
                    value(hourly, "wind_direction_10m", i, 0.0),
                    value(hourly, "temperature_2m", i, 20.0),
                    value(hourly, "relative_humidity_2m", i, 30.0)));
        }
        return hours;
    }

    private static double value(JsonNode hourly, String field, int i, double fallback) {
        JsonNode node = hourly.path(field).path(i);
        return node.isNumber() ? node.asDouble() : fallback;
    }
}
