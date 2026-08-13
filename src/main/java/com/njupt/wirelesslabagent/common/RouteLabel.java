package com.njupt.wirelesslabagent.common;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** 查询分类标签及其对应的执行策略。 */
public enum RouteLabel {
    DEVICE(RagStrategy.NONE),
    FOLLOW_UP(RagStrategy.COMPRESS),
    AMBIGUOUS(RagStrategy.REWRITE),
    COMPLEX(RagStrategy.MULTI),
    ENGLISH(RagStrategy.TRANSLATE),
    CHAT(RagStrategy.NONE),
    STATIC(RagStrategy.SINGLE),
    UNKNOWN(RagStrategy.SINGLE);

    private final RagStrategy strategy;

    RouteLabel(RagStrategy strategy) {
        this.strategy = strategy;
    }

    public RagStrategy strategy() {
        return strategy;
    }

    public static Optional<RouteLabel> parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return Optional.empty();
        }
        String normalized = modelOutput.toUpperCase(Locale.ROOT);
        for (RouteLabel label : values()) {
            if (label == UNKNOWN) {
                continue;
            }
            if (Pattern.compile("(?<![A-Z_])" + label.name() + "(?![A-Z_])")
                    .matcher(normalized)
                    .find()) {
                return Optional.of(label);
            }
        }
        return Optional.empty();
    }
}
