package com.example.hadaq.DTOOut;

// one zone in a simulation result
public record ZoneSummaryDTOOut(double peakMoisture, int hoursAboveThreshold, double erosionIndex) {
}
