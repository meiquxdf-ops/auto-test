package com.atest.web.dto;

import java.time.Instant;
import java.util.List;

public record AgentView(
        String agentId,
        String displayTag,
        String status,
        boolean online,
        String version,
        String bootId,
        String sessionId,
        String remoteAddr,
        List<String> aliases,
        int concurrency,
        int maxConcurrency,
        int runningCount,
        int activeCount,
        boolean idle,
        Instant connectedAt,
        Instant disconnectedAt,
        Instant lastHeartbeatAt) {
}
