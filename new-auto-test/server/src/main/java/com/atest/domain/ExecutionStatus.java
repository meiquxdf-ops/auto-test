package com.atest.domain;

import java.util.Locale;
import java.util.Set;

/** execution: pending -> dispatching -> running -> pass|fail|block|exception|canceled */
public enum ExecutionStatus {
    PENDING,
    DISPATCHING,
    RUNNING,
    PASS,
    FAIL,
    BLOCK,
    EXCEPTION,
    CANCELED;

    /** The only statuses a conditionConfig rule may produce. */
    public static final Set<ExecutionStatus> JUDGEABLE = Set.of(PASS, FAIL, BLOCK, EXCEPTION);

    public boolean isTerminal() {
        return this == PASS || this == FAIL || this == BLOCK || this == EXCEPTION || this == CANCELED;
    }

    public boolean isActive() {
        return this == DISPATCHING || this == RUNNING;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ExecutionStatus fromWire(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (ExecutionStatus st : values()) {
            if (st.name().toLowerCase(Locale.ROOT).equals(s)) {
                return st;
            }
        }
        return null;
    }

    /** Parses a status coming from a conditionConfig rule; only pass/fail/block/exception are legal. */
    public static ExecutionStatus fromJudgeValue(String v) {
        ExecutionStatus st = fromWire(v);
        return st != null && JUDGEABLE.contains(st) ? st : null;
    }
}
