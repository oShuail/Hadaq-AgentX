package com.example.hadaq.Service;

import com.example.hadaq.DTOOut.V2Out;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

// core API -> AI worker: POST {AI_URL}/ai/process with a ProcessRequest. The worker answers 202 at once and calls back later
@Service
public class AiWorkerClient {

    private final RestClient restClient;
    private final String aiUrl;

    public AiWorkerClient(RestClient.Builder builder, @Value("${hadaq.ai.url:http://localhost:8100}") String aiUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(10_000);
        this.aiUrl = aiUrl;
        this.restClient = builder.clone().baseUrl(aiUrl).requestFactory(factory).build();
    }

    public String url() {
        return aiUrl;
    }

    // throws when the worker is not reachable or refuses the job
    public void process(V2Out.ProcessRequest request) {
        restClient.post().uri("/ai/process")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }
}
