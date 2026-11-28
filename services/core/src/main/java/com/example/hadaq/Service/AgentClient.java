package com.example.hadaq.Service;

import com.example.hadaq.Config.ExecutorConfig;
import com.example.hadaq.DTOOut.EventDTOOut;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

// forwards storm_started, moisture_anomaly and detection_ready to the agents (POST {agents}/agents/trigger).
// In the background: a slow or stopped agents service never blocks a reading or an upload
@Slf4j
@Service
public class AgentClient {

    private final RestClient restClient;
    private final TaskExecutor worker;
    private final boolean enabled;

    public AgentClient(RestClient.Builder builder,
                       @Qualifier(ExecutorConfig.WORKER) TaskExecutor worker,
                       @Value("${hadaq.agents-url:http://localhost:8100}") String agentsUrl,
                       @Value("${hadaq.agents-enabled:true}") boolean enabled) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(3_000);
        this.restClient = builder.clone().baseUrl(agentsUrl).requestFactory(factory).build();
        this.worker = worker;
        this.enabled = enabled;
    }

    public void trigger(EventDTOOut event) {
        if (!enabled) {
            return;
        }
        worker.execute(() -> {
            try {
                restClient.post().uri("/agents/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(event)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception exception) {
                log.info("Agents not reachable ({}); event {} not forwarded", exception.getMessage(), event.type().value());
            }
        });
    }
}
