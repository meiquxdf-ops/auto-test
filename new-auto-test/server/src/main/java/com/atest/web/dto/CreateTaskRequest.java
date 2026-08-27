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
}
