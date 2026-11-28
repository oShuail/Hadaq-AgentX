package com.example.hadaq.Repository;

import com.example.hadaq.Entity.Detection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DetectionRepository extends JpaRepository<Detection, String> {

    Optional<Detection> findFirstByBuildingIdOrderByCreatedAtDesc(String buildingId);

    List<Detection> findByPhotoId(String photoId);

    // for change detection: the recent detections of the other photos (filtered by camera in the service)
    List<Detection> findTop200ByBuildingIdAndPhotoIdNotOrderByCreatedAtDesc(String buildingId, String photoId);

    // all detections of the most recently analysed photo
    default List<Detection> findLatestSet(String buildingId) {
        return findFirstByBuildingIdOrderByCreatedAtDesc(buildingId)
                .map(last -> findByPhotoId(last.getPhotoId()))
                .orElse(List.of());
    }
}
