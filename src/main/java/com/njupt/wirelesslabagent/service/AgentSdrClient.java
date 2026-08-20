package com.njupt.wirelesslabagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.njupt.wirelesslabagent.config.SdrResilienceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class AgentSdrClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String agentMode;

    public AgentSdrClient(RestClient.Builder builder,
                          ObjectMapper objectMapper,
                          SdrResilienceProperties resilienceProperties,
                          @Value("${sdr.agent-base-url:http://127.0.0.1:8000}") String baseUrl,
                          @Value("${sdr.agent-mode:think}") String agentMode) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(resilienceProperties.getHttpConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(resilienceProperties.getHttpReadTimeout());
        this.restClient = builder.requestFactory(requestFactory).baseUrl(baseUrl).build();
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
        return pretty(executeInstructionResult(instruction));
    }

    public JsonNode executeInstructionResult(String instruction) {
        return post("/api/chat", Map.of(
                "instruction", instruction,
                "session_id", "spring-bridge-" + UUID.randomUUID(),
                "mode", agentMode
        ));
    }

    public String executeToolFallback(String toolName, String toolInput) {
        if ("stop_hardware_task".equals(toolName)) {
            return pretty(stopHardwareTask());
        }
        String instruction = """
                MCP 主通道当前不可用，请通过 HTTP Bridge 执行降级任务。
                目标工具：%s
                参数 JSON：%s
                必须保持参数原值并调用真实工具；若设备、驱动或参数不可用，返回真实错误，禁止编造成功结果。
                """.formatted(toolName, toolInput);
        return executeInstruction(instruction);
    }

    private JsonNode get(String uri) {
        try {
            JsonNode body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
            return body != null ? body : error("EMPTY_RESPONSE", "Agent_SDR 返回空响应", true);
        } catch (RestClientResponseException exception) {
            log.warn("Agent_SDR GET {} returned {}", uri, exception.getStatusCode());
            return error("HTTP_STATUS", "Agent_SDR 返回 HTTP " + exception.getStatusCode(),
                    exception.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException exception) {
            log.warn("Agent_SDR GET {} failed: {}", uri, exception.getMessage());
            return transportError(exception);
        } catch (Exception exception) {
            log.warn("Agent_SDR GET {} failed: {}", uri, exception.getMessage());
            return error("HTTP_CLIENT_ERROR", "Agent_SDR 调用失败: " + exception.getMessage(), false);
        }
    }

    private JsonNode post(String uri, Object payload) {
        try {
            JsonNode body = restClient.post().uri(uri).body(payload).retrieve().body(JsonNode.class);
            return body != null ? body : error("EMPTY_RESPONSE", "Agent_SDR 返回空响应", true);
        } catch (RestClientResponseException exception) {
            log.warn("Agent_SDR POST {} returned {}", uri, exception.getStatusCode());
            return error("HTTP_STATUS", "Agent_SDR 返回 HTTP " + exception.getStatusCode(),
                    exception.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException exception) {
            log.warn("Agent_SDR POST {} failed: {}", uri, exception.getMessage());
            return transportError(exception);
        } catch (Exception exception) {
            log.warn("Agent_SDR POST {} failed: {}", uri, exception.getMessage());
            return error("HTTP_CLIENT_ERROR", "Agent_SDR 调用失败: " + exception.getMessage(), false);
        }
    }

    private JsonNode transportError(Throwable exception) {
        boolean timeout = hasCause(exception, SocketTimeoutException.class)
                || hasCause(exception, HttpTimeoutException.class)
                || hasCause(exception, TimeoutException.class);
        String type = timeout ? "HTTP_TIMEOUT" : "HTTP_CONNECTION_ERROR";
        String message = timeout ? "Agent_SDR 响应超时" : "Agent_SDR 未连接";
        return error(type, message + ": " + exception.getMessage(), true);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private JsonNode error(String errorType, String message, boolean retryable) {
        return objectMapper.createObjectNode()
                .put("status", "error")
                .put("connected", false)
                .put("errorType", errorType)
                .put("retryable", retryable)
                .put("message", message);
    }

    private String pretty(JsonNode response) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
        } catch (Exception exception) {
            return response.toString();
        }
    }
}
