package com.example.hadaq.Entity;

import com.example.hadaq.Enum.Scenario;
import com.example.hadaq.Enum.SimulationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// one run of the moisture model. request / result = the contract JSON (SimulationRequest / SimulationResult)
@Entity
@Table(name = "simulation_runs", indexes = {
        @Index(name = "idx_sim_building_status_created", columnList = "building_id, status, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class SimulationRun {

    @Id
    @Column(name = "run_id", length = 40)
    private String runId;

    @Column(name = "building_id", nullable = false, length = 10)
    private String buildingId;

    @Column(name = "wall_id", nullable = false, length = 10)
    private String wallId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Scenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SimulationStatus status;

    @Column(name = "request_json", nullable = false, columnDefinition = "LONGTEXT")
    private String requestJson;

    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(length = 1000)
    private String error;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;
}
