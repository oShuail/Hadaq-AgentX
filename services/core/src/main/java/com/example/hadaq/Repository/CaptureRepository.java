package com.example.hadaq.Repository;

import com.example.hadaq.Entity.Capture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CaptureRepository extends JpaRepository<Capture, String> {

    Optional<Capture> findByClientUuid(String clientUuid);

    List<Capture> findByInspectionIdOrderByCapturedAtAsc(String inspectionId);

    Optional<Capture> findFirstByBuildingIdOrderByCapturedAtDesc(String buildingId);
}
