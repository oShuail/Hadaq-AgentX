package com.example.hadaq.Service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/*
 * core API -> simulation service (Basma, services/simulation, :8200): POST {SIM_URL}/sim/run (T5).
 * Empty SIM_URL = not used: the core runs its Java port of bucket_v0. The service answers with a SimulationResult
 * (plus its own extras, such as the first hour each zone crosses critical), which the core keeps as it is.
 */
@Service
public class SimServiceClient {

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String simUrl;

    public SimServiceClient(RestClient.Builder builder, JsonMapper jsonMapper, @Value("${hadaq.sim.url:}") String simUrl) {
        this.jsonMapper = jsonMapper;
        this.simUrl = simUrl == null ? "" : simUrl.trim().replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(120_000);  // a 7-day forecast run can take a while; the call runs in the background
        this.restClient = builder.clone().requestFactory(factory).build();
    }

    public boolean enabled() {
        return !simUrl.isEmpty();
    }

    public String url() {
        return simUrl;
    }

    // throws when the service is down or answers with an error
    public JsonNode run(Map<String, Object> request) {
        // sent as bytes so the request has a Content-Length: an object body goes out chunked, which simple servers refuse
        byte[] body = jsonMapper.writeValueAsBytes(request);
        return restClient.post().uri(simUrl + "/sim/run")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }
}
