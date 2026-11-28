package com.example.hadaq.Simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// parameters of bucket_v0 (port of hadaq_sim.moisture.WallParams, owner: Basma).
// Every value is ASSUMED: literature-scale guesses, not tested on At-Turaif adobe.
public class WallParams {

    public double azimuthDeg = 270.0;            // direction the facade faces (outward), degrees from north
    public int rows = 4;
    public int cols = 8;
    public double exposureFe = 1.0;              // open, low-rise site
    public double[] rowDepositionFd = {0.35, 0.45, 0.6, 1.0};  // R1 (base) .. R4 (top)
    public double edgeColFactor = 1.2;           // corners catch more rain
    public double absorbCapacityMmH = 2.0;       // water a dry surface takes in per hour
    public double storageMm = 25.0;              // water a zone holds at full saturation (m = 1)
    public double splashFactor = 0.15;           // share of horizontal rain splashing onto the base row
    public double catchmentFactor = 1.0;         // mm of ground water at the foot per mm of rain
    public double pondDrainMmH = 0.6;            // how fast the pool at the foot drains away
    public double pondMaxMm = 40.0;              // deepest pool before it overflows elsewhere
    public double capillaryMmH = 0.8;            // max uptake from the pool into the base row
    public double baseDryingFactor = 0.5;        // base dries at half speed (ground contact, shade)
    public double dryingRate = 0.02;             // fraction lost per hour at 1 kPa VPD, calm air
    public double initialMoisture = 0.15;        // dry-season starting state
    public double threshold = 0.6;               // moisture fraction we treat as critical
    public final List<String> assumptions = new ArrayList<>();

    public WallParams() {
    }

    public WallParams(double azimuthDeg, int rows, int cols) {
        this.azimuthDeg = azimuthDeg;
        this.rows = rows;
        this.cols = cols;
    }

    // deposition factor of a row; walls with more than 4 rows use the top value above R4
    public double deposition(int rowIndex) {
        return rowDepositionFd[Math.min(rowIndex, rowDepositionFd.length - 1)];
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "Facade faces %.0f deg; zones %dx%d (R1 = base).", azimuthDeg, rows, cols));
        lines.add(String.format(Locale.ROOT, "Exposure F_E=%s, deposition by row %s, corners x%s.",
                fmt(exposureFe), tuple(rowDepositionFd), fmt(edgeColFactor)));
        lines.add(String.format(Locale.ROOT, "Absorption %s mm/h, storage %s mm, splash %s.",
                fmt(absorbCapacityMmH), fmt(storageMm), fmt(splashFactor)));
        lines.add(String.format(Locale.ROOT, "Pool at the foot: catchment x%s, drains %s mm/h, capillary rise up to %s mm/h.",
                fmt(catchmentFactor), fmt(pondDrainMmH), fmt(capillaryMmH)));
        lines.add(String.format(Locale.ROOT, "Drying %s/h per kPa VPD; start moisture %s; critical %s.",
                fmt(dryingRate), fmt(initialMoisture), fmt(threshold)));
        lines.add("All parameters ASSUMED; not calibrated on At-Turaif adobe.");
        lines.addAll(assumptions);
        return lines;
    }

    // Python prints floats like 0.6 / 1.0 / 3.0000000000000004; keep it short and readable
    private static String fmt(double value) {
        return Double.toString(value);
    }

    private static String tuple(double[] values) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i]);
        }
        return sb.append(")").toString();
    }
}
