package com.njupt.wirelesslabagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        return post("/api/hardware/stop", Map.of(
                "operation_id", newOperationId("http-stop")
        ));
    }

    public String executeInstruction(String instruction) {
        return pretty(executeInstructionResult(instruction, newOperationId("http-chat")));
    }

    public JsonNode executeInstructionResult(String instruction) {
        return executeInstructionResult(instruction, newOperationId("http-chat"));
    }

    public JsonNode executeInstructionResult(String instruction, String operationId) {
        return post("/api/chat", Map.of(
                "instruction", instruction,
                "session_id", "spring-bridge-" + UUID.randomUUID(),
                "mode", agentMode,
                "operation_id", operationId
        ));
    }

    public String executeToolFallback(String toolName, String toolInput, String operationId) {
        JsonNode arguments;
        try {
            arguments = objectMapper.readTree(toolInput);
            if (arguments == null || !arguments.isObject()) {
                arguments = objectMapper.createObjectNode();
            }
            if (arguments.isObject()) {
                ((ObjectNode) arguments).remove("operation_id");
            }
        } catch (Exception exception) {
            return pretty(error("INVALID_TOOL_ARGUMENTS",
                    "HTTP 降级参数不是合法 JSON: " + exception.getMessage(), false));
        }
        return pretty(post("/api/tools/execute", Map.of(
                "operation_id", operationId,
                "tool_name", toolName,
                "arguments", arguments
        )));
    }

    public JsonNode operationStatus(String operationId) {
        return get("/api/operations/" + operationId);
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

    private String newOperationId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
