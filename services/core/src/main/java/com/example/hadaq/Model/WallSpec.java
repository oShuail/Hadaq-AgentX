package com.example.hadaq.Model;

// one wall in contracts/building_b01.json. azimuthDeg = the direction the facade faces
public record WallSpec(String id, String buildingId, double azimuthDeg, int rows, int cols) {
}
