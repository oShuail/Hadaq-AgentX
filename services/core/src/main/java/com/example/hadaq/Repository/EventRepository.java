package com.example.hadaq.Repository;

import com.example.hadaq.Entity.Event;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, String> {

    List<Event> findByBuildingIdOrderByTsDesc(String buildingId, Limit limit);
}
