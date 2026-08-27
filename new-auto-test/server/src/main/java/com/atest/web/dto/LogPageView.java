package com.atest.web.dto;

import java.util.List;

public record LogPageView(
        String executeId,
        int from,
        int nextSeq,
        int minSeq,
        int maxSeq,
        /** true when the 5MB tail cap dropped older lines */
        boolean truncated,
        long logBytes,
        long maxBytes,
        boolean hasMore,
        String status,
        boolean finished,
        List<LogLineView> lines) {
}
