package com.example.hadaq.Repository;

import com.example.hadaq.Entity.SensorReading;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {

    // history of one sensor, newest first
    List<SensorReading> findByBuildingIdAndSensorIdOrderByTsDescIdDesc(String buildingId, String sensorId, Limit limit);

    // history of every sensor of the building, newest first
    List<SensorReading> findByBuildingIdOrderByTsDescIdDesc(String buildingId, Limit limit);

    Optional<SensorReading> findFirstBySensorIdOrderByTsDescIdDesc(String sensorId);
}
