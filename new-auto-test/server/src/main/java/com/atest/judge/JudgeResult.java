package com.atest.judge;

import com.atest.domain.ExecutionStatus;

public record JudgeResult(ExecutionStatus status, String reason, String matchedRule) {

    public static JudgeResult of(ExecutionStatus status, String reason) {
        return new JudgeResult(status, reason, null);
    }

    public static JudgeResult of(ExecutionStatus status, String reason, String matchedRule) {
        return new JudgeResult(status, reason, matchedRule);
    }
}
