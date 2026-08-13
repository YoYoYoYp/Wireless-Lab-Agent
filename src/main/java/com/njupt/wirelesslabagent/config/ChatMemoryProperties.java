package com.njupt.wirelesslabagent.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Component
@Validated
@ConfigurationProperties(prefix = "chat.memory")
public class ChatMemoryProperties {

    @Min(1)
    private int windowSize = 20;
    @NotNull
    private Duration ttl = Duration.ofDays(7);
    @Min(1)
    private int summaryBatchSize = 6;
    @Min(1)
    private int summaryMaxChars = 1_200;

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("chat.memory.ttl 必须大于 0");
        }
        this.ttl = ttl;
    }

    public int getSummaryBatchSize() {
        return summaryBatchSize;
    }

    public void setSummaryBatchSize(int summaryBatchSize) {
        this.summaryBatchSize = summaryBatchSize;
    }

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = summaryMaxChars;
    }
}
