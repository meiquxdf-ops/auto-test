package com.atest.web.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

/**
 * One HTTP request that creates several tasks (different commands / targets) sharing one
 * requestId. Partial success: an invalid item only rejects that item (reported in
 * {@code errors[{index,message}]}), the rest are created. Only a missing / malformed / duplicate
 * requestId — or every single item failing — rejects the whole request.
 */
@Getter
@Setter
public class BatchCreateTaskRequest {

    private String requestId;

    /** applied to every task of the batch */
    private String callbackUrl;

    private List<Item> items;

    @Getter
    @Setter
    public static class Item {

        private String name;

        private String command;

        private String cwd;

        private Map<String, String> env;

        /** each item is a displayTag or an agentId, or {"type":"tag|agentId","value":"..."} */
        private List<JsonNode> targets;

        private JsonNode conditionConfig;

        private String operator;

        private Integer timeoutSec;
    }
}
