package com.atest.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

public record TaskView(
        Long id,
        String name,
        String command,
        String cwd,
        Map<String, String> env,
        JsonNode conditionConfig,
        List<String> targets,
        String operator,
        int timeoutSec,
        int priority,
        long queueOrder,
        String status,
        int totalCount,
        Long rerunOf,
        String requestId,
        String callbackUrl,
        String callbackStatus,
        int callbackAttempts,
        String callbackLastError,
        Instant callbackLastAt,
        Map<String, Long> statusCounts,
        long attachmentCount,
        List<ExecutionView> executions,
        Instant createdAt,
        Instant updatedAt) {
}
