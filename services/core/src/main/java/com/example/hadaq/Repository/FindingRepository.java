package com.example.hadaq.Repository;

import com.example.hadaq.Entity.Finding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FindingRepository extends JpaRepository<Finding, String> {

    List<Finding> findByInspectionIdOrderByCreatedAtAsc(String inspectionId);

    List<Finding> findByCaptureId(String captureId);

    List<Finding> findByInspectionIdIn(List<String> inspectionIds);
}
