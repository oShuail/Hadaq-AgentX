package com.example.hadaq.Entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// records that have no table of their own but must be citable by the agents (risk scores: risk_ + 12 hex)
@Entity
@Table(name = "evidence", indexes = {
        @Index(name = "idx_evidence_building_kind", columnList = "building_id, kind")
})
@Getter
@Setter
@NoArgsConstructor
public class Evidence {

    @Id
    @Column(name = "evidence_id", length = 40)
    private String evidenceId;

    @Column(name = "building_id", nullable = false, length = 10)
    private String buildingId;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(name = "data_json", nullable = false, columnDefinition = "LONGTEXT")
    private String dataJson;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;
}
