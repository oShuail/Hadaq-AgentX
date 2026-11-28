package com.example.hadaq.Entity;

import com.example.hadaq.Entity.Converter.V2Converters;
import com.example.hadaq.Model.V2Values;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// a round of photos for the surveyor app: which walls, which shots (v2, srv_ + 12 hex)
@Entity
@Table(name = "surveys", indexes = @Index(name = "idx_survey_assignee", columnList = "assignee"))
@Getter
@Setter
@NoArgsConstructor
public class Survey {

    @Id
    @Column(name = "survey_id", length = 40)
    private String surveyId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 60)
    private String assignee;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant due;

    @Convert(converter = V2Converters.SurveyTasks.class)
    @Column(columnDefinition = "LONGTEXT")
    private List<V2Values.SurveyTask> tasks = new ArrayList<>();

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;
}
