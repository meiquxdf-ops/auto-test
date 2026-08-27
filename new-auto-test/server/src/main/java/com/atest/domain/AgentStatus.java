package com.atest.domain;

import java.util.Locale;

public enum AgentStatus {
    ONLINE,
    OFFLINE;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
