package com.njupt.wirelesslabagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.njupt.wirelesslabagent.config.SdrResilienceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SdrToolExecutionGatewayTest {

    private SdrToolExecutionGateway gateway;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.shutdown();
        }
    }

    @Test
    void shouldRetryReadOnlyToolAndReturnPrimarySuccess() throws Exception {
        ToolCallback query = callback("query_usrp_device_parameters");
        when(query.call(anyString()))
                .thenThrow(new IllegalStateException("temporary mcp failure"))
                .thenReturn("{\"status\":\"success\"}");
        AgentSdrClient http = mock(AgentSdrClient.class);
        gateway = gateway(http, properties(3, 2), query);

        String result = gateway.mcpTools().getToolCallbacks()[0].call("{}");

        assertTrue(result.contains("success"));
        ArgumentCaptor<String> inputs = ArgumentCaptor.forClass(String.class);
        verify(query, times(2)).call(inputs.capture());
        String firstOperationId = new ObjectMapper().readTree(inputs.getAllValues().get(0))
                .path("operation_id").asText();
        String secondOperationId = new ObjectMapper().readTree(inputs.getAllValues().get(1))
                .path("operation_id").asText();
        assertFalse(firstOperationId.isBlank());
        assertEquals(firstOperationId, secondOperationId);
        verifyNoInteractions(http);
        assertEquals(SdrCircuitBreaker.State.CLOSED, gateway.circuitState());
    }

    @Test
    void shouldNotRetryOrFallbackAfterSideEffectInvocationFails() throws Exception {
        ToolCallback transmit = callback("text_fsk_send_and_receive");
        when(transmit.call(anyString())).thenThrow(new IllegalStateException("connection lost"));
        AgentSdrClient http = mock(AgentSdrClient.class);
        gateway = gateway(http, properties(3, 2), transmit);

        String result = gateway.mcpTools().getToolCallbacks()[0].call("{\"text\":\"NJUPT\"}");

        var json = new ObjectMapper().readTree(result);
        assertEquals("error", json.path("status").asText());
        assertEquals("UNKNOWN", json.path("executionState").asText());
        assertFalse(json.path("retryable").asBoolean());
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(transmit).call(input.capture());
        String operationId = new ObjectMapper().readTree(input.getValue())
                .path("operation_id").asText();
        assertEquals(operationId, json.path("operationId").asText());
        verify(http).operationStatus(operationId);
    }

    @Test
    void shouldReturnBusinessErrorWithoutRetryOrFallback() {
        ToolCallback query = callback("query_usrp_device_parameters");
        when(query.call(anyString()))
                .thenReturn("{\"status\":\"error\",\"error\":\"USRP 未连接\"}");
        AgentSdrClient http = mock(AgentSdrClient.class);
        gateway = gateway(http, properties(1, 2), query);

        String result = gateway.mcpTools().getToolCallbacks()[0].call("{}");

        assertTrue(result.contains("USRP 未连接"));
        verify(query).call(anyString());
        verifyNoInteractions(http);
        assertEquals(SdrCircuitBreaker.State.CLOSED, gateway.circuitState());
    }

    @Test
    void shouldReuseSameOperationIdWhenReadOnlyToolFallsBackToHttp() throws Exception {
        ToolCallback query = callback("query_usrp_device_parameters");
        when(query.call(anyString())).thenThrow(new IllegalStateException("mcp down"));
        AgentSdrClient http = mock(AgentSdrClient.class);
        when(http.executeToolFallback(anyString(), anyString(), anyString()))
                .thenReturn("{\"status\":\"success\",\"operationState\":\"SUCCESS\"}");
        gateway = gateway(http, properties(3, 1), query);

        gateway.mcpTools().getToolCallbacks()[0].call("{}");

        ArgumentCaptor<String> mcpInput = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> httpOperationId = ArgumentCaptor.forClass(String.class);
        verify(query).call(mcpInput.capture());
        verify(http).executeToolFallback(
                eq("query_usrp_device_parameters"), eq("{}"), httpOperationId.capture());
        String mcpOperationId = new ObjectMapper().readTree(mcpInput.getValue())
                .path("operation_id").asText();
        assertEquals(mcpOperationId, httpOperationId.getValue());
    }

    @Test
    void shouldReconcileSideEffectFromOperationStateWithoutExecutingHttpFallback() throws Exception {
        ToolCallback transmit = callback("text_fsk_send_and_receive");
        when(transmit.call(anyString())).thenThrow(new IllegalStateException("response lost"));
        AgentSdrClient http = mock(AgentSdrClient.class);
        when(http.operationStatus(anyString())).thenAnswer(invocation -> {
            String operationId = invocation.getArgument(0, String.class);
            return new ObjectMapper().readTree("""
                    {"status":"success","operationState":"SUCCESS","operationId":"%s"}
                    """.formatted(operationId));
        });
        gateway = gateway(http, properties(3, 1), transmit);

        String result = gateway.mcpTools().getToolCallbacks()[0]
                .call("{\"text\":\"NJUPT\"}");

        var json = new ObjectMapper().readTree(result);
        assertEquals("SUCCESS", json.path("operationState").asText());
        assertTrue(json.path("reconciledAfterMcpFailure").asBoolean());
        verify(http, never()).executeToolFallback(anyString(), anyString(), anyString());
    }

    @Test
    void shouldTreatRemoteInputValidationFailureAsBusinessError() throws Exception {
        ToolCallback query = callback("query_usrp_device_parameters");
        when(query.call(anyString()))
                .thenThrow(new IllegalArgumentException("Validation error: field required"));
        AgentSdrClient http = mock(AgentSdrClient.class);
        gateway = gateway(http, properties(1, 2), query);

        String result = gateway.mcpTools().getToolCallbacks()[0].call("{}");

        var json = new ObjectMapper().readTree(result);
        assertEquals("INVALID_TOOL_ARGUMENTS", json.path("errorType").asText());
        assertFalse(json.path("retryable").asBoolean());
        verify(query).call(anyString());
        verifyNoInteractions(http);
        assertEquals(SdrCircuitBreaker.State.CLOSED, gateway.circuitState());
    }

    @Test
    void shouldSkipMcpAndFallbackBeforeSideEffectWhenCircuitIsOpen() {
        ToolCallback query = callback("query_usrp_device_parameters");
        ToolCallback transmit = callback("text_fsk_send_and_receive");
        when(query.call(anyString())).thenThrow(new IllegalStateException("mcp down"));
        AgentSdrClient http = mock(AgentSdrClient.class);
        when(http.executeToolFallback(anyString(), anyString(), anyString()))
                .thenReturn("{\"status\":\"success\"}");
        gateway = gateway(http, properties(1, 1), query, transmit);

        String queryResult = gateway.mcpTools().getToolCallbacks()[0].call("{}");
        String transmitResult = gateway.mcpTools().getToolCallbacks()[1]
                .call("{\"text\":\"NJUPT\"}");

        assertTrue(queryResult.contains("\"degraded\" : true"));
        assertTrue(transmitResult.contains("\"degraded\" : true"));
        verify(transmit, never()).call(anyString());
        verify(http).executeToolFallback(
                eq("text_fsk_send_and_receive"), anyString(), anyString());
    }

    private SdrToolExecutionGateway gateway(AgentSdrClient http,
                                            SdrResilienceProperties properties,
                                            ToolCallback... callbacks) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ToolCallbackProvider.from(callbacks));
        return new SdrToolExecutionGateway(http, new ObjectMapper(), properties, provider);
    }

    private SdrResilienceProperties properties(int threshold, int attempts) {
        SdrResilienceProperties properties = new SdrResilienceProperties();
        properties.setFailureThreshold(threshold);
        properties.setSafeMaxAttempts(attempts);
        properties.setRetryBackoff(Duration.ZERO);
        properties.setReadTimeout(Duration.ofSeconds(1));
        properties.setActionTimeout(Duration.ofSeconds(1));
        properties.setOpenDuration(Duration.ofMinutes(1));
        return properties;
    }

    private ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
