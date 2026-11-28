package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.DTOIn.SurveyRequestDTOIn;
import com.example.hadaq.DTOOut.V2Out;
import com.example.hadaq.Entity.Survey;
import com.example.hadaq.Enum.Role;
import com.example.hadaq.Enum.TaskStatus;
import com.example.hadaq.Mapper.V2Mapper;
import com.example.hadaq.Model.V2Values;
import com.example.hadaq.Repository.SurveyRepository;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Roles;
import com.example.hadaq.Util.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyService {

    private static final Pattern RANGE = Pattern.compile("^C(\\d+)-C(\\d+)$");

    private final SurveyRepository surveyRepository;
    private final RegistryService registryService;
    private final V2RegistryService v2RegistryService;

    @Value("${hadaq.seed-survey:true}")
    private boolean seedSurvey;

    // POST /api/v2/surveys (site_manager only): a new round of photos for the surveyor app
    public V2Out.Survey create(SurveyRequestDTOIn request, String user) {
        Roles.require(user, Role.SITE_MANAGER);
        List<V2Values.SurveyTask> tasks = new ArrayList<>();
        Set<String> walls = new HashSet<>();
        for (SurveyRequestDTOIn.Task task : request.tasks()) {
            V2Out.Wall wall = v2RegistryService.wall(task.buildingId(), task.wallId());  // 404 building, 400 wall
            if (!walls.add(task.buildingId() + "/" + task.wallId())) {
                throw new ApiException("Wall " + task.wallId() + " of " + task.buildingId() + " is listed twice; put all its shots in one task");
            }
            for (String shot : task.requiredShots()) {
                Matcher m = RANGE.matcher(shot);
                if (m.matches()) {
                    int from = Integer.parseInt(m.group(1)), to = Integer.parseInt(m.group(2));
                    if (from < 1 || from > to || to > wall.cols()) {
                        throw new ApiException("Shot " + shot + " is outside wall " + wall.id() + " (columns C1 to C" + wall.cols() + ")");
                    }
                }
            }
            tasks.add(new V2Values.SurveyTask(task.buildingId(), wall.id(), new ArrayList<>(new LinkedHashSet<>(task.requiredShots())),
                    new ArrayList<>(), TaskStatus.NOT_STARTED));
        }
        Survey survey = new Survey();
        survey.setSurveyId(Ids.newId("srv"));
        survey.setTitle(request.title().trim());
        survey.setAssignee(request.assignee().trim());
        survey.setDue(Times.parse(request.due()));
        survey.setTasks(tasks);
        survey.setCreatedAt(Times.now());
        surveyRepository.save(survey);
        log.info("Survey {} created for {} ({} walls)", survey.getSurveyId(), survey.getAssignee(), tasks.size());
        return V2Mapper.toDTO(survey);
    }

    // GET /api/v2/surveys?assignee=surveyor (the surveyor app's task list)
    public List<V2Out.Survey> list(String assignee) {
        List<Survey> surveys = assignee == null || assignee.isBlank()
                ? surveyRepository.findAllByOrderByCreatedAtDesc()
                : surveyRepository.findByAssigneeOrderByCreatedAtDesc(assignee);
        return surveys.stream().map(V2Mapper::toDTO).toList();
    }

    // a shot arrived: mark it done in the survey's task for that wall (partial until every required shot is in)
    public void markShot(String surveyId, String buildingId, String wallId, String segment) {
        if (surveyId == null) {
            return;
        }
        surveyRepository.findById(surveyId).ifPresentOrElse(survey -> {
            List<V2Values.SurveyTask> tasks = new ArrayList<>();
            for (V2Values.SurveyTask t : survey.getTasks()) {
                if (t.buildingId().equals(buildingId) && t.wallId().equals(wallId)) {
                    List<String> done = new ArrayList<>(t.doneShots());
                    if (!done.contains(segment)) {
                        done.add(segment);
                    }
                    TaskStatus status = done.containsAll(t.requiredShots()) ? TaskStatus.DONE : TaskStatus.PARTIAL;
                    t = new V2Values.SurveyTask(t.buildingId(), t.wallId(), t.requiredShots(), done, status);
                }
                tasks.add(t);
            }
            survey.setTasks(tasks);
            surveyRepository.save(survey);
        }, () -> log.info("Capture names survey {} that does not exist; stored without a survey update", surveyId));
    }

    // a first survey so the surveyor app has something to do (like the contract example)
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!seedSurvey || surveyRepository.count() > 0) {
            return;
        }
        Survey survey = new Survey();
        survey.setSurveyId(Ids.newId("srv"));
        survey.setTitle("Qasr Salwa, winter cycle");
        survey.setAssignee("surveyor");
        survey.setDue(Times.now().plus(Duration.ofDays(3)));
        survey.setTasks(new ArrayList<>(List.of(new V2Values.SurveyTask(registryService.buildingId(), "W01",
                List.of("full"), List.of(), TaskStatus.NOT_STARTED))));
        survey.setCreatedAt(Times.now());
        surveyRepository.save(survey);
        log.info("Seeded survey {} for the surveyor app", survey.getSurveyId());
    }
}
