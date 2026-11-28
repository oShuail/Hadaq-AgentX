package com.example.hadaq.Repository;

import com.example.hadaq.Entity.Inspection;
import com.example.hadaq.Enum.InspectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InspectionRepository extends JpaRepository<Inspection, String> {

    // the open inspection a new capture of this wall (in this survey) joins
    Optional<Inspection> findFirstByBuildingIdAndWallIdAndSurveyIdAndStatusOrderByStartedAtDesc(
            String buildingId, String wallId, String surveyId, InspectionStatus status);

    Optional<Inspection> findFirstByBuildingIdAndWallIdAndSurveyIdIsNullAndStatusOrderByStartedAtDesc(
            String buildingId, String wallId, InspectionStatus status);

    List<Inspection> findByBuildingIdOrderByStartedAtDesc(String buildingId);

    Optional<Inspection> findFirstByBuildingIdAndWallIdAndStatusOrderByApprovedAtDesc(
            String buildingId, String wallId, InspectionStatus status);
}
