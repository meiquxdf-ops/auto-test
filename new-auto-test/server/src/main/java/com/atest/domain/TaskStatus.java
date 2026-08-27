package com.atest.domain;

import java.util.Locale;

public enum TaskStatus {
    PENDING,
    RUNNING,
    FINISHED,
    CANCELED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TaskStatus fromWire(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        for (TaskStatus st : values()) {
            if (st.wire().equals(s)) {
                return st;
            }
        }
        return null;
    }
}
