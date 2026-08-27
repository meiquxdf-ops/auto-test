package com.atest.web.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PatchAgentRequest {

    /** rename, must stay unique across agents */
    private String displayTag;

    /** alias */
    private String tag;

    /** 1..atest.concurrency.max-value, only accepted while the agent is idle */
    private Integer concurrency;

    public String resolvedTag() {
        if (displayTag != null && !displayTag.isBlank()) {
            return displayTag.trim();
        }
        return tag == null || tag.isBlank() ? null : tag.trim();
    }
}
