package com.njupt.wirelesslabagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class AgentSdrClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String agentMode;

    public AgentSdrClient(RestClient.Builder builder,
                          ObjectMapper objectMapper,
                          @Value("${sdr.agent-base-url:http://127.0.0.1:8000}") String baseUrl,
                          @Value("${sdr.agent-mode:think}") String agentMode) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.agentMode = agentMode;
    }

    public JsonNode health() {
        return get("/api/health");
    }

    public JsonNode hardwareStatus() {
        return get("/api/hardware_status");
    }

    public JsonNode visualization() {
        return get("/api/visualization");
    }

    public JsonNode stopHardwareTask() {
        return post("/api/hardware/stop", Map.of());
    }

    public String executeInstruction(String instruction) {
        JsonNode response = post("/api/chat", Map.of(
                "instruction", instruction,
                "session_id", "spring-bridge-" + UUID.randomUUID(),
                "mode", agentMode
        ));
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
        } catch (Exception exception) {
            return response.toString();
        }
    }

    private JsonNode get(String uri) {
        try {
            JsonNode body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
            return body != null ? body : error("Agent_SDR 返回空响应");
        } catch (Exception exception) {
            log.warn("Agent_SDR GET {} failed: {}", uri, exception.getMessage());
            return error("Agent_SDR 未连接: " + exception.getMessage());
        }
    }

    private JsonNode post(String uri, Object payload) {
        try {
            JsonNode body = restClient.post().uri(uri).body(payload).retrieve().body(JsonNode.class);
            return body != null ? body : error("Agent_SDR 返回空响应");
        } catch (Exception exception) {
            log.warn("Agent_SDR POST {} failed: {}", uri, exception.getMessage());
            return error("Agent_SDR 未连接: " + exception.getMessage());
        }
    }

    private JsonNode error(String message) {
        return objectMapper.createObjectNode()
                .put("status", "error")
                .put("connected", false)
                .put("message", message);
    }
}
