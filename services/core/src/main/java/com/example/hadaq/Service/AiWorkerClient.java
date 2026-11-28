package com.example.hadaq.Service;

import com.example.hadaq.DTOOut.V2Out;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

// core API -> AI worker: POST {AI_URL}/ai/process with a ProcessRequest. The worker answers 202 at once and calls back later
@Service
public class AiWorkerClient {

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String aiUrl;

    public AiWorkerClient(RestClient.Builder builder, JsonMapper jsonMapper, @Value("${hadaq.ai.url:http://localhost:8100}") String aiUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(10_000);
        this.aiUrl = aiUrl;
        this.jsonMapper = jsonMapper;
        this.restClient = builder.clone().baseUrl(aiUrl).requestFactory(factory).build();
    }

    public String url() {
        return aiUrl;
    }

    // throws when the worker is not reachable or refuses the job
    public void process(V2Out.ProcessRequest request) {
        // sent as bytes so the request has a Content-Length: an object body goes out chunked, which simple servers refuse
        byte[] body = jsonMapper.writeValueAsBytes(request);
        restClient.post().uri("/ai/process")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
