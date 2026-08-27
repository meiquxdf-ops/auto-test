package com.atest.web.dto;

import java.time.Instant;

public record LogLineView(int seq, String line, Instant ts) {
}
