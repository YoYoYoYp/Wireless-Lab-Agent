package com.njupt.wirelesslabagent.chatmemory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationKeyFactoryTest {

    private final ConversationKeyFactory factory = new ConversationKeyFactory();

    @Test
    void shouldIsolateSameChatIdBetweenUsers() {
        assertNotEquals(
                factory.scopedConversationId("user-a", "conv-1"),
                factory.scopedConversationId("user-b", "conv-1"));
    }

    @Test
    void shouldAvoidDelimiterAmbiguity() {
        assertNotEquals(
                factory.scopedConversationId("a:b", "c"),
                factory.scopedConversationId("a", "b:c"));
    }

    @Test
    void shouldRejectBlankIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.scopedConversationId(" ", "conv-1"));
        assertThrows(IllegalArgumentException.class,
                () -> factory.scopedConversationId("user-a", " "));
    }
}
