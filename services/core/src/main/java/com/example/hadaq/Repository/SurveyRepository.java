package com.example.hadaq.Repository;

import com.example.hadaq.Entity.Survey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SurveyRepository extends JpaRepository<Survey, String> {

    List<Survey> findByAssigneeOrderByCreatedAtDesc(String assignee);

    List<Survey> findAllByOrderByCreatedAtDesc();
}
