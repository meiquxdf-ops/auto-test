package com.atest.domain;

import java.util.Locale;

/**
 * Delivery state of the one-shot result callback a caller may attach to a task.
 * none -> (task has callbackUrl) pending -> running -> success | failed
 */
public enum CallbackStatus {
    /** no callbackUrl on the task, nothing will ever be sent */
    NONE,
    /** callbackUrl set, task not yet delivered (waiting for terminal state or for the sender) */
    PENDING,
    /** a sender claimed the task and delivery attempts are in flight */
    RUNNING,
    /** a 2xx response was received */
    SUCCESS,
    /** every attempt exhausted without a 2xx */
    FAILED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CallbackStatus fromWire(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        for (CallbackStatus st : values()) {
            if (st.wire().equals(s)) {
                return st;
            }
        }
        return null;
    }
}
