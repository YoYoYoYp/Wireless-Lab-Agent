package com.njupt.wirelesslabagent.service;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 面向单个 Agent_SDR MCP 通道的轻量熔断器。半开探测只允许无副作用操作进入。
 */
public final class SdrCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier nanoTime;
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openUntilNanos;

    public SdrCircuitBreaker(int failureThreshold, Duration openDuration) {
        this(failureThreshold, openDuration, System::nanoTime);
    }

    SdrCircuitBreaker(int failureThreshold, Duration openDuration, LongSupplier nanoTime) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openNanos = openDuration.toNanos();
        this.nanoTime = nanoTime;
    }

    public synchronized boolean tryAcquirePermission(boolean safeProbe) {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.HALF_OPEN) {
            return false;
        }
        if (nanoTime.getAsLong() < openUntilNanos || !safeProbe) {
            return false;
        }
        state = State.HALF_OPEN;
        return true;
    }

    public synchronized void recordSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        openUntilNanos = 0L;
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN || ++consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openUntilNanos = nanoTime.getAsLong() + openNanos;
        }
    }

    public synchronized State state() {
        return state;
    }
}
