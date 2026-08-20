package com.njupt.wirelesslabagent.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdrCircuitBreakerTest {

    @Test
    void shouldOpenAfterThresholdAndCloseAfterSafeHalfOpenProbe() {
        AtomicLong clock = new AtomicLong();
        SdrCircuitBreaker breaker = new SdrCircuitBreaker(
                2, Duration.ofSeconds(30), clock::get);

        assertTrue(breaker.tryAcquirePermission(true));
        breaker.recordFailure();
        assertTrue(breaker.tryAcquirePermission(true));
        breaker.recordFailure();
        assertEquals(SdrCircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquirePermission(true));

        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        assertFalse(breaker.tryAcquirePermission(false));
        assertTrue(breaker.tryAcquirePermission(true));
        assertEquals(SdrCircuitBreaker.State.HALF_OPEN, breaker.state());

        breaker.recordSuccess();
        assertEquals(SdrCircuitBreaker.State.CLOSED, breaker.state());
    }
}
