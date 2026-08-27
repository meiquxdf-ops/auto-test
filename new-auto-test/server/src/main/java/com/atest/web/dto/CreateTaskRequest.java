package com.atest.web.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateTaskRequest {

    private String name;

    @NotEmpty(message = "不能为空")
    private String command;

    private String cwd;

    private Map<String, String> env;

    /** each item is a displayTag or an agentId, or {"type":"tag|agentId","value":"..."} */
    @NotEmpty(message = "至少指定一个目标")
    private List<JsonNode> targets;

    private JsonNode conditionConfig;

    private String operator;

    private Integer timeoutSec;

    private Integer priority;

    /** open-API idempotency / query key: ^[A-Za-z0-9._-]{1,64}$, globally unique; blank -> the server mints a UUID and returns it */
    private String requestId;

    /** optional http(s) URL POSTed once when the task reaches finished / canceled */
    private String callbackUrl;
}
