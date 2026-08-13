package com.njupt.wirelesslabagent.service;

import com.njupt.wirelesslabagent.common.RagStrategy;
import com.njupt.wirelesslabagent.common.RouteLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueryRoutingServiceTest {

    @Test
    void shouldRouteHardwareKeywordsWithoutCallingClassifier() {
        ChatModel model = mock(ChatModel.class);
        QueryRoutingService service = new QueryRoutingService(model);
        clearInvocations(model);
        var decision = service.route("请用 USRP 扫频寻找干净频点");

        assertEquals(RouteLabel.DEVICE, decision.label());
        assertEquals(RagStrategy.NONE, decision.strategy());
        assertFalse(decision.fallback());
        verifyNoInteractions(model);
    }

    @Test
    void shouldNotTreatKnowledgeQuestionContainingModulationAsHardwareCommand() {
        var decision = serviceReturning("STATIC").route("BPSK 为什么适合低信噪比链路？");

        assertEquals(RouteLabel.STATIC, decision.label());
        assertEquals(RagStrategy.SINGLE, decision.strategy());
    }

    @ParameterizedTest
    @CsvSource({
            "DEVICE,NONE", "FOLLOW_UP,COMPRESS", "AMBIGUOUS,REWRITE",
            "COMPLEX,MULTI", "ENGLISH,TRANSLATE", "CHAT,NONE", "STATIC,SINGLE"
    })
    void shouldMapClassifierLabelsToStrategies(String label, String strategy) {
        var decision = serviceReturning("分类结果：" + label).route("测试消息");

        assertEquals(RouteLabel.valueOf(label), decision.label());
        assertEquals(RagStrategy.valueOf(strategy), decision.strategy());
        assertFalse(decision.fallback());
    }

    @Test
    void shouldFallbackToSingleWhenClassifierReturnsUnknownLabel() {
        var decision = serviceReturning("OTHER").route("测试消息");

        assertEquals(RouteLabel.UNKNOWN, decision.label());
        assertEquals(RagStrategy.SINGLE, decision.strategy());
        assertTrue(decision.fallback());
    }

    @Test
    void shouldFallbackToSingleWhenClassifierFails() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("model unavailable"));

        var decision = new QueryRoutingService(model).route("测试消息");

        assertEquals(RagStrategy.SINGLE, decision.strategy());
        assertTrue(decision.fallback());
        assertEquals("classifier-error", decision.reason());
    }

    private QueryRoutingService serviceReturning(String output) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage(output)))));
        return new QueryRoutingService(model);
    }
}
