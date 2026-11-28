package com.example.hadaq.Repository;

import com.example.hadaq.Entity.SimulationRun;
import com.example.hadaq.Enum.SimulationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SimulationRunRepository extends JpaRepository<SimulationRun, String> {

    Optional<SimulationRun> findFirstByBuildingIdAndStatusOrderByCreatedAtDesc(String buildingId, SimulationStatus status);
}
