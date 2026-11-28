package com.example.hadaq.Simulation;

import com.example.hadaq.DTOIn.SimulationRequestDTOIn;
import com.example.hadaq.DTOOut.SimulationResultDTOOut;
import com.example.hadaq.DTOOut.ZoneSummaryDTOOut;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.Scenario;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Numbers;
import com.example.hadaq.Util.Times;
import com.example.hadaq.Util.ZoneIds;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * bucket_v0: a simple, transparent wall moisture model (port of hadaq_sim.moisture, owner: Basma).
 *
 * Each wall zone (4 rows x 8 columns) is a "bucket" of water storage. Every hour:
 * 1. Wind-driven rain hits the facade (ASHRAE 160 form):  r_bv = F_E * F_D * F_L * U * cos(theta) * r_h
 *    theta = angle between where the wind comes from and the direction the facade faces.
 * 2. Each zone absorbs what it can (less when already wet). The rest runs DOWN to the zone below.
 * 3. The base row (R1) also gets ground splash, and water that reaches the ground pools at the wall foot.
 *    The pool drains slowly and keeps feeding the base by capillary rise after the rain stops.
 * 4. Zones dry depending on air dryness (vapour pressure deficit) and wind; the base dries slower.
 * 5. An erosion index accumulates: water hitting the surface x wind x wetness.
 *
 * Every parameter is ASSUMED. This is a demo model, not a validated hygrothermal simulation.
 */
public final class MoistureModel {

    public static final String ENGINE = "bucket_v0";
    private static final double F_L = 0.2;  // ASHRAE 160 empirical constant, kg.s/(m3.mm)

    private MoistureModel() {
    }

    static double vpdKpa(double tempC, double rh) {
        double saturation = 0.6108 * Math.exp(17.27 * tempC / (tempC + 237.3));
        return Math.max(0.0, saturation * (1 - rh / 100.0));
    }

    // cos(theta) when the wind blows onto the facade, else 0
    static double facingFactor(double windFromDeg, double facadeAzimuthDeg) {
        return Math.max(0.0, Math.cos(Math.toRadians(windFromDeg - facadeAzimuthDeg)));
    }

    // interventions and what-ifs are boundary changes, not new physics
    static WallParams applyScenario(WallParams params, Scenario scenario) {
        if (scenario == Scenario.DRAINAGE_FIXED) {
            params.splashFactor *= 0.5;
            params.catchmentFactor *= 0.3;
            params.pondDrainMmH *= 5;
            params.assumptions.add("Scenario drainage_fixed: graded ground and a drain at the foot; splash halved, "
                    + "pooling cut by 70%, drains 5x faster (ASSUMED effect).");
        }
        return params;
    }

    public static SimulationResultDTOOut run(SimulationRequestDTOIn request, List<WeatherHour> weather,
                                             WallParams wallParams, String weatherSource) {
        WallParams params = applyScenario(wallParams == null ? new WallParams() : wallParams, request.scenario());
        double rainScale = request.scenario() == Scenario.RAIN_X2 ? 2.0 : 1.0;
        if (rainScale != 1.0) {
            params.assumptions.add("Scenario rain_x2: every hour of rain doubled.");
        }

        int rows = params.rows;
        int cols = params.cols;
        double[][] moisture = new double[rows][cols];   // moisture[rowIndex][colIndex], row 0 = R1
        double[][] erosion = new double[rows][cols];
        double[] pond = new double[cols];               // water pooled at the foot of each column, mm
        for (double[] row : moisture) {
            java.util.Arrays.fill(row, params.initialMoisture);
        }
        Map<String, List<Double>> series = new LinkedHashMap<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                series.put(ZoneIds.zoneId(request.buildingId(), request.wallId(), r + 1, c + 1), new ArrayList<>());
            }
        }

        for (WeatherHour hour : weather) {
            double rH = hour.rainMm() * rainScale;
            double drive = params.exposureFe * F_L * hour.windMs() * facingFactor(hour.windDirDeg(), params.azimuthDeg) * rH;
            double vpd = vpdKpa(hour.tempC(), hour.rh());
            for (int c = 0; c < cols; c++) {
                double colFactor = (c == 0 || c == cols - 1) ? params.edgeColFactor : 1.0;
                double runoff = 0.0;
                for (int r = rows - 1; r >= 0; r--) {  // top row first, water runs down
                    double incoming = drive * params.deposition(r) * colFactor + runoff;
                    if (r == 0) {
                        incoming += params.splashFactor * rH;
                    }
                    double room = params.absorbCapacityMmH * (1.0 - moisture[r][c]);
                    double absorbed = Math.min(incoming, room);
                    runoff = incoming - absorbed;
                    moisture[r][c] += absorbed / params.storageMm;
                    erosion[r][c] += incoming * Math.sqrt(Math.max(hour.windMs(), 0.1)) * moisture[r][c];
                }
                // water reaching the ground pools at the foot, then feeds the base row by capillary rise
                pond[c] = Math.min(params.pondMaxMm, pond[c] + params.catchmentFactor * rH + runoff);
                double uptake = Math.min(pond[c], params.capillaryMmH * (1.0 - moisture[0][c]));
                moisture[0][c] += uptake / params.storageMm;
                pond[c] = Math.max(0.0, pond[c] - uptake - params.pondDrainMmH - 0.1 * vpd);
                for (int r = 0; r < rows; r++) {
                    double dry = params.dryingRate * vpd * (1 + 0.1 * hour.windMs()) * moisture[r][c];
                    moisture[r][c] -= dry * (r == 0 ? params.baseDryingFactor : 1.0);
                    moisture[r][c] = Math.min(1.0, Math.max(0.0, moisture[r][c]));
                }
            }
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    series.get(ZoneIds.zoneId(request.buildingId(), request.wallId(), r + 1, c + 1))
                            .add(Numbers.round(moisture[r][c], 4));
                }
            }
        }

        Map<String, ZoneSummaryDTOOut> summary = new LinkedHashMap<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                String zid = ZoneIds.zoneId(request.buildingId(), request.wallId(), r + 1, c + 1);
                List<Double> values = series.get(zid);
                double peak = values.isEmpty() ? params.initialMoisture : values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                int hoursAbove = (int) values.stream().filter(v -> v >= params.threshold).count();
                summary.put(zid, new ZoneSummaryDTOOut(Numbers.round(peak, 4), hoursAbove, Numbers.round(erosion[r][c], 3)));
            }
        }

        List<OffsetDateTime> times = weather.stream().map(WeatherHour::time).toList();
        return new SimulationResultDTOOut(Ids.newId("sim"), request.buildingId(), request.wallId(), request.scenario(),
                ENGINE, Provenance.MODEL_OUTPUT, Times.now(), times, series, summary, params.threshold,
                params.describe(), weatherSource);
    }
}
