package com.example.hadaq.Service;

import com.example.hadaq.DTOIn.EventDTOIn;
import com.example.hadaq.DTOOut.EventDTOOut;
import com.example.hadaq.Entity.Event;
import com.example.hadaq.Enum.EventSource;
import com.example.hadaq.Enum.EventType;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Events.HadaqEventStored;
import org.springframework.context.ApplicationEventPublisher;
import com.example.hadaq.Mapper.DTOMapper;
import com.example.hadaq.Repository.EventRepository;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Numbers;
import com.example.hadaq.Util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class EventService {

    // the agents investigate these on their own
    private static final Set<EventType> AGENT_TRIGGERS = Set.of(EventType.STORM_STARTED, EventType.MOISTURE_ANOMALY, EventType.DETECTION_READY);

    private final EventRepository eventRepository;
    private final RegistryService registryService;
    private final LiveService liveService;
    private final AgentClient agentClient;
    private final AnomalyCooldownService anomalyCooldownService;
    private final RiskService riskService;
    private final ObjectProvider<MqttService> mqttService;  // lazy: MqttService also calls back into this service
    private final ApplicationEventPublisher applicationEvents;

    public EventService(EventRepository eventRepository, RegistryService registryService, LiveService liveService,
                        AgentClient agentClient, AnomalyCooldownService anomalyCooldownService, RiskService riskService,
                        ObjectProvider<MqttService> mqttService, ApplicationEventPublisher applicationEvents) {
        this.applicationEvents = applicationEvents;
        this.eventRepository = eventRepository;
        this.registryService = registryService;
        this.liveService = liveService;
        this.agentClient = agentClient;
        this.anomalyCooldownService = anomalyCooldownService;
        this.riskService = riskService;
        this.mqttService = mqttService;
    }

    public static Event create(EventType type, String buildingId, EventSource source, Provenance provenance, Map<String, Object> payload) {
        Event event = new Event();
        event.setEventId(Ids.newId("evt"));
        event.setType(type);
        event.setBuildingId(buildingId);
        event.setTs(Times.now());
        event.setSource(source);
        event.setProvenance(provenance);
        event.setPayload(payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload));
        return event;
    }

    // POST /events (scenario panel, agents, simulators over HTTP)
    public EventDTOOut post(EventDTOIn dto) {
        registryService.check(dto.buildingId());
        if (dto.type() == EventType.DEMO_RESET) {
            anomalyCooldownService.clear();
            riskService.invalidate(dto.buildingId());
        }
        Event event = DTOMapper.toEntity(dto);
        handle(event, true);
        return DTOMapper.toDTO(event);
    }

    // an event that arrived over MQTT (it is already on the broker, so it is not published again)
    public void receive(EventDTOIn dto) {
        handle(DTOMapper.toEntity(dto), false);
    }

    /*
     * store (once), push live, publish to MQTT when it came another way, forward to the agents when worth it.
     * returns false if this event_id was already stored (our own MQTT echo)
     */
    public boolean handle(Event event, boolean publishMqtt) {
        if (eventRepository.existsById(event.getEventId())) {
            return false;
        }
        try {
            eventRepository.save(event);
        } catch (DataIntegrityViolationException duplicate) {
            return false;  // the echo arrived between the check and the insert
        }
        EventDTOOut dto = DTOMapper.toDTO(event);
        liveService.broadcast(LiveService.EVENT, dto);
        applicationEvents.publishEvent(new HadaqEventStored(dto));
        if (publishMqtt) {
            mqttService.ifAvailable(mqtt -> mqtt.publishEvent(dto));
        }
        boolean worthInvestigating = event.getType() != EventType.DETECTION_READY || hasChanges(event.getPayload());
        if (AGENT_TRIGGERS.contains(event.getType()) && event.getSource() != EventSource.AGENT && worthInvestigating) {
            agentClient.trigger(dto);
        }
        return true;
    }

    public List<EventDTOOut> recent(String buildingId, int limit) {
        return eventRepository.findByBuildingIdOrderByTsDesc(buildingId, org.springframework.data.domain.Limit.of(Numbers.clamp(limit, 1, 500)))
                .stream().map(DTOMapper::toDTO).toList();
    }

    private static boolean hasChanges(Map<String, Object> payload) {
        Object changed = payload == null ? null : payload.get("changed");
        return changed instanceof Collection<?> list ? !list.isEmpty() : changed != null && !Boolean.FALSE.equals(changed);
    }
}
