package com.atest.web.dto;

import java.time.Instant;

public record AttachmentView(
        Long id,
        Long taskId,
        String executeId,
        String name,
        long size,
        String contentType,
        Instant createdAt) {
}
