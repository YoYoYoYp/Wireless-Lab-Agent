package com.njupt.wirelesslabagent.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Component
@Validated
@ConfigurationProperties(prefix = "sdr.resilience")
public class SdrResilienceProperties {

    private boolean enabled = true;
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(8);
    @NotNull
    private Duration actionTimeout = Duration.ofSeconds(60);
    @Min(1)
    private int safeMaxAttempts = 2;
    @NotNull
    private Duration retryBackoff = Duration.ofMillis(200);
    @Min(1)
    private int failureThreshold = 3;
    @NotNull
    private Duration openDuration = Duration.ofSeconds(30);
    @NotNull
    private Duration httpConnectTimeout = Duration.ofSeconds(3);
    @NotNull
    private Duration httpReadTimeout = Duration.ofSeconds(75);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = positive(readTimeout, "read-timeout");
    }

    public Duration getActionTimeout() {
        return actionTimeout;
    }

    public void setActionTimeout(Duration actionTimeout) {
        this.actionTimeout = positive(actionTimeout, "action-timeout");
    }

    public int getSafeMaxAttempts() {
        return safeMaxAttempts;
    }

    public void setSafeMaxAttempts(int safeMaxAttempts) {
        this.safeMaxAttempts = safeMaxAttempts;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = nonNegative(retryBackoff, "retry-backoff");
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public Duration getOpenDuration() {
        return openDuration;
    }

    public void setOpenDuration(Duration openDuration) {
        this.openDuration = positive(openDuration, "open-duration");
    }

    public Duration getHttpConnectTimeout() {
        return httpConnectTimeout;
    }

    public void setHttpConnectTimeout(Duration httpConnectTimeout) {
        this.httpConnectTimeout = positive(httpConnectTimeout, "http-connect-timeout");
    }

    public Duration getHttpReadTimeout() {
        return httpReadTimeout;
    }

    public void setHttpReadTimeout(Duration httpReadTimeout) {
        this.httpReadTimeout = positive(httpReadTimeout, "http-read-timeout");
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("sdr.resilience." + name + " 必须大于 0");
        }
        return value;
    }

    private Duration nonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException("sdr.resilience." + name + " 不能小于 0");
        }
        return value;
    }
}
