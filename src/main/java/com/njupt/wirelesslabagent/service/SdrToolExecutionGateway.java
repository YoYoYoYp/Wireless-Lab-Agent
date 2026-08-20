package com.njupt.wirelesslabagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.njupt.wirelesslabagent.config.SdrResilienceProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent_SDR 统一调用层：包装 MCP ToolCallback，执行超时、有限安全重试、熔断和 HTTP Bridge 降级。
 */
@Service
@Slf4j
public class SdrToolExecutionGateway {

    private final AgentSdrClient agentSdrClient;
    private final ObjectMapper objectMapper;
    private final SdrResilienceProperties properties;
    private final SdrCircuitBreaker circuitBreaker;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("sdr-mcp-call-", 0).factory());
    private final ToolCallbackProvider resilientProvider;

    public SdrToolExecutionGateway(AgentSdrClient agentSdrClient,
                                   ObjectMapper objectMapper,
                                   SdrResilienceProperties properties,
                                   ObjectProvider<ToolCallbackProvider> provider) {
        this.agentSdrClient = agentSdrClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.circuitBreaker = new SdrCircuitBreaker(
                properties.getFailureThreshold(), properties.getOpenDuration());

        ToolCallbackProvider original = provider.getIfAvailable();
        if (original == null) {
            this.resilientProvider = null;
        } else if (!properties.isEnabled()) {
            this.resilientProvider = original;
        } else {
            ToolCallback[] callbacks = Arrays.stream(original.getToolCallbacks())
                    .map(this::wrap)
                    .toArray(ToolCallback[]::new);
            this.resilientProvider = ToolCallbackProvider.from(callbacks);
        }
    }

    public boolean hasMcpTools() {
        return resilientProvider != null && resilientProvider.getToolCallbacks().length > 0;
    }

    public ToolCallbackProvider mcpTools() {
        return hasMcpTools() ? resilientProvider : null;
    }

    public SdrCircuitBreaker.State circuitState() {
        return circuitBreaker.state();
    }

    private ToolCallback wrap(ToolCallback delegate) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                return execute(delegate, toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return execute(delegate, toolInput, toolContext);
            }
        };
    }

    private String execute(ToolCallback delegate, String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        SdrOperationKind kind = SdrOperationKind.fromToolName(toolName);
        int maxAttempts = kind.retrySafe() ? properties.getSafeMaxAttempts() : 1;
        GatewayFailure lastFailure = null;
        boolean primaryAttempted = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!circuitBreaker.tryAcquirePermission(kind.retrySafe())) {
                break;
            }
            primaryAttempted = true;
            try {
                String result = invokeWithTimeout(delegate, toolInput, toolContext, timeoutFor(kind));
                circuitBreaker.recordSuccess();
                return result;
            } catch (GatewayFailure failure) {
                lastFailure = failure;
                if (!failure.transportFailure) {
                    circuitBreaker.recordSuccess();
                    return businessFailure(toolName, failure);
                }
                circuitBreaker.recordFailure();
                log.warn("Agent_SDR MCP tool {} attempt {}/{} failed: {}",
                        toolName, attempt, maxAttempts, failure.getMessage());
                if (attempt < maxAttempts && properties.getRetryBackoff().toMillis() > 0) {
                    sleepBackoff(properties.getRetryBackoff());
                }
            }
        }

        if (!primaryAttempted) {
            return fallback(toolName, toolInput, "mcp-circuit-open");
        }
        if (kind.retrySafe()) {
            String reason = lastFailure == null ? "mcp-unavailable" : lastFailure.errorType;
            return fallback(toolName, toolInput, reason);
        }
        return uncertainSideEffect(toolName, lastFailure);
    }

    private String invokeWithTimeout(ToolCallback delegate,
                                     String toolInput,
                                     ToolContext toolContext,
                                     Duration timeout) {
        Future<String> future = executor.submit(() -> toolContext == null
                ? delegate.call(toolInput)
                : delegate.call(toolInput, toolContext));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new GatewayFailure("MCP_TIMEOUT", "MCP 工具响应超时", exception, true);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new GatewayFailure("MCP_INTERRUPTED", "MCP 工具调用被中断", exception, true);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            boolean businessFailure = isToolInputFailure(cause);
            throw new GatewayFailure(
                    businessFailure ? "INVALID_TOOL_ARGUMENTS" : "MCP_CALL_FAILED",
                    safeMessage(cause), cause, !businessFailure);
        }
    }

    private Duration timeoutFor(SdrOperationKind kind) {
        return kind == SdrOperationKind.READ_ONLY
                ? properties.getReadTimeout()
                : properties.getActionTimeout();
    }

    private String fallback(String toolName, String toolInput, String reason) {
        log.warn("Agent_SDR MCP tool {} degraded to HTTP Bridge, reason={}", toolName, reason);
        String raw = agentSdrClient.executeToolFallback(toolName, toolInput);
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            if (parsed instanceof ObjectNode object) {
                object.put("channel", "http-bridge");
                object.put("degraded", true);
                object.put("fallbackReason", reason);
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
            }
        } catch (Exception ignored) {
            // 保留原始降级结果，避免为了添加元数据覆盖真实工具响应。
        }
        return raw;
    }

    private String uncertainSideEffect(String toolName, GatewayFailure failure) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "error");
        result.put("errorType", failure == null ? "MCP_CALL_FAILED" : failure.errorType);
        result.put("retryable", false);
        result.put("executionState", "UNKNOWN");
        result.put("tool", toolName);
        result.put("message", "MCP 调用失败，硬件动作是否已经执行无法确认；为避免重复操作，未自动重试或切换 HTTP Bridge，请先查询设备和任务状态。");
        return result.toPrettyString();
    }

    private String businessFailure(String toolName, GatewayFailure failure) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "error");
        result.put("errorType", failure.errorType);
        result.put("retryable", false);
        result.put("tool", toolName);
        result.put("message", failure.getMessage());
        return result.toPrettyString();
    }

    private boolean isToolInputFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("validation error")
                        || normalized.contains("field required")
                        || normalized.contains("input should")
                        || normalized.contains("extra inputs are not permitted")
                        || normalized.contains("invalid argument")
                        || normalized.contains("参数校验")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepBackoff(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class GatewayFailure extends RuntimeException {
        private final String errorType;
        private final boolean transportFailure;

        private GatewayFailure(String errorType, String message, Throwable cause, boolean transportFailure) {
            super(message, cause);
            this.errorType = errorType;
            this.transportFailure = transportFailure;
        }
    }
}
