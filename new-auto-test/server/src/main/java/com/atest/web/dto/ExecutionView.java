package com.atest.web.dto;

import java.time.Instant;

public record ExecutionView(
        Long id,
        String executeId,
        Long taskId,
        String taskName,
        String agentId,
        String agentTag,
        String targetRaw,
        String status,
        String subStatus,
        boolean disconnected,
        Integer exitCode,
        String lastLine,
        String reason,
        String matchedRule,
        int logSeq,
        int logMinSeq,
        long logBytes,
        boolean truncated,
        int attempt,
        boolean cancelRequested,
        boolean timeoutRequested,
        String command,
        String cwd,
        Integer timeoutSec,
        Instant dispatchedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant leaseExpireAt,
        Instant createdAt,
        Instant updatedAt) {
}
