package com.njupt.wirelesslabagent.chatmemory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisChatMemoryRepositoryTest {

    @Test
    void shouldFindMessagesEvictedFromWindowFront() {
        List<String> previous = List.of("m1", "m2", "m3", "m4");
        List<String> current = List.of("m3", "m4", "m5", "m6");

        assertEquals(List.of("m1", "m2"),
                RedisChatMemoryRepository.findEvictedPrefix(previous, current));
    }

    @Test
    void shouldNotTreatAppendAsEviction() {
        List<String> previous = List.of("m1", "m2");
        List<String> current = List.of("m1", "m2", "m3");

        assertEquals(List.of(),
                RedisChatMemoryRepository.findEvictedPrefix(previous, current));
    }

    @Test
    void shouldTreatReplacedHistoryAsEvicted() {
        assertEquals(List.of("m1", "m2"),
                RedisChatMemoryRepository.findEvictedPrefix(
                        List.of("m1", "m2"), List.of("other")));
    }
}
