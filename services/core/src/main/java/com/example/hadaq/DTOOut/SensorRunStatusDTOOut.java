package com.example.hadaq.DTOOut;

// Saleh's two-minute B01 run. available = the sensor simulator (sim-01) is online
public record SensorRunStatusDTOOut(boolean available, boolean running, double elapsed, double duration) {
}
