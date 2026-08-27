package com.atest.web.dto;

import java.time.Instant;

public record TimelineItemView(
        String source,
        Long id,
        String type,
        String agentId,
        String executeId,
        Long taskId,
        String message,
        Instant ts) {
}
