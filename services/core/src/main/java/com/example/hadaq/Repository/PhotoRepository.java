package com.example.hadaq.Repository;

import com.example.hadaq.Entity.Photo;
import com.example.hadaq.Enum.PhotoStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, String> {

    // work queue for the vision worker: oldest first
    List<Photo> findByBuildingIdAndStatusOrderByCapturedAtAsc(String buildingId, PhotoStatus status, Limit limit);

    // browsing: newest first
    List<Photo> findByBuildingIdOrderByCapturedAtDesc(String buildingId, Limit limit);
}
