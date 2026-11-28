package com.example.hadaq.Simulation;

import com.example.hadaq.DTOOut.RiskScoreDTOOut;
import com.example.hadaq.DTOOut.SimulationResultDTOOut;
import com.example.hadaq.DTOOut.ZoneSummaryDTOOut;
import com.example.hadaq.Entity.Detection;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Enum.RiskLevel;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Numbers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Risk per zone with an adapted ABC method (ICCROM / CCI), port of hadaq_sim.risk (owner: Basma).
 * A: how often the damaging event happens (5 = about once a year)
 * B: share of value lost in each affected part (5 = total loss, 4 = 10%, 3 = 1%, 2 = 0.1%)
 * C: share of the asset's value affected (5 = the whole asset)
 * Magnitude = A + B + C (max 15). Our adaptation and the level bands are ASSUMED, to review with a conservator.
 */
public final class RiskScorer {

    public static final String METHOD = "ABC method (adapted, see hadaq_sim.risk)";

    private RiskScorer() {
    }

    // map a fraction (or rate per year) to the ABC log scale and clamp to [0.5, 5]
    static double logScore(double fraction) {
        if (fraction <= 0) {
            return 0.5;
        }
        return Numbers.round(Math.min(5.0, Math.max(0.5, 5.0 + Math.log10(fraction))), 2);
    }

    static double scoreA(double heavyRainDaysPerYear) {
        return logScore(heavyRainDaysPerYear);
    }

    // ASSUMED mapping from model outputs to expected loss of value per event
    static double lossFraction(double peak, int hoursAbove, double threshold, Double crackGrowthPct) {
        double fraction = 0.0001;
        if (peak >= threshold) {
            fraction = 0.003;
        }
        if (hoursAbove >= 12) {
            fraction = 0.01;
        }
        if (hoursAbove >= 36) {
            fraction = 0.03;
        }
        if (crackGrowthPct != null && crackGrowthPct > 10) {
            fraction *= 3;
        }
        return Math.min(fraction, 1.0);
    }

    static RiskLevel levelFor(double magnitude) {
        if (magnitude >= 12) {
            return RiskLevel.VERY_HIGH;
        }
        if (magnitude >= 11) {
            return RiskLevel.HIGH;
        }
        if (magnitude >= 10) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    public static List<RiskScoreDTOOut> scoreBuilding(SimulationResultDTOOut sim, List<Detection> detections,
                                                      double heavyRainDaysPerYear, Map<String, Double> zoneWeights,
                                                      double defaultWeight) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String zid : sim.summary().keySet()) {
            weights.put(zid, zoneWeights == null ? defaultWeight : zoneWeights.getOrDefault(zid, defaultWeight));
        }
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight == 0) {
            totalWeight = 1.0;
        }
        Map<String, Double> growthByZone = new HashMap<>();
        Map<String, List<String>> detIdsByZone = new HashMap<>();
        for (Detection det : detections) {
            detIdsByZone.computeIfAbsent(det.getZoneId(), k -> new ArrayList<>()).add(det.getDetectionId());
            if (det.getGrowthPct() != null) {
                growthByZone.merge(det.getZoneId(), Math.max(0.0, det.getGrowthPct()), Math::max);
            }
        }

        double a = scoreA(heavyRainDaysPerYear);
        List<RiskScoreDTOOut> scores = new ArrayList<>();
        for (Map.Entry<String, ZoneSummaryDTOOut> entry : sim.summary().entrySet()) {
            String zid = entry.getKey();
            ZoneSummaryDTOOut summary = entry.getValue();
            double b = logScore(lossFraction(summary.peakMoisture(), summary.hoursAboveThreshold(), sim.threshold(), growthByZone.get(zid)));
            double c = logScore(weights.get(zid) / totalWeight);
            double magnitude = Numbers.round(a + b + c, 2);
            List<String> evidence = new ArrayList<>();
            evidence.add(sim.runId());
            evidence.addAll(detIdsByZone.getOrDefault(zid, List.of()));
            scores.add(new RiskScoreDTOOut(Ids.newId("risk"), sim.buildingId(), zid, "rain", a, b, c, magnitude,
                    levelFor(magnitude), evidence, METHOD, Provenance.MODEL_OUTPUT));
        }
        // stable sort like Python's sorted(..., reverse=True): equal magnitudes keep their zone order
        scores.sort(Comparator.comparingDouble(RiskScoreDTOOut::magnitude).reversed());
        return scores;
    }
}
