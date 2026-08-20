package com.njupt.wirelesslabagent.service;

import java.util.Locale;

public enum SdrOperationKind {
    READ_ONLY(true),
    IDEMPOTENT_CONTROL(true),
    SIDE_EFFECT(false);

    private final boolean retrySafe;

    SdrOperationKind(boolean retrySafe) {
        this.retrySafe = retrySafe;
    }

    public boolean retrySafe() {
        return retrySafe;
    }

    public static SdrOperationKind fromToolName(String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (name.startsWith("stop_") || name.contains("stop_hardware")) {
            return IDEMPOTENT_CONTROL;
        }
        if (name.startsWith("query_")
                || name.startsWith("search_")
                || name.contains("status")
                || name.contains("diagnostic")
                || name.contains("physical_scan")) {
            return READ_ONLY;
        }
        return SIDE_EFFECT;
    }
}
