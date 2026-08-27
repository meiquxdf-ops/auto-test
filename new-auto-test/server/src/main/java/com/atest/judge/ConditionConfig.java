package com.atest.judge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.atest.common.Json;
import com.atest.domain.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parsed conditionConfig.
 *
 * <pre>
 * {"rules":[{"op":"equals","value":"0","status":"pass"}],"other":"fail"}
 * </pre>
 *
 * A bare array of rules is accepted too. Unknown/blank operators and statuses are rejected so a
 * broken config fails fast at task creation instead of silently mis-judging a run.
 */
public final class ConditionConfig {

    private final List<ConditionRule> rules;
    private final ExecutionStatus other;

    public ConditionConfig(List<ConditionRule> rules, ExecutionStatus other) {
        this.rules = rules == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(rules));
        this.other = other;
    }

    public List<ConditionRule> getRules() {
        return rules;
    }

    public ExecutionStatus getOther() {
        return other;
    }

    public boolean isEmpty() {
        return rules.isEmpty() && other == null;
    }

    public static ConditionConfig parse(String json) {
        return parse(Json.read(json));
    }

    /** @throws IllegalArgumentException when the config is present but malformed */
    public static ConditionConfig parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        JsonNode rulesNode;
        JsonNode otherNode = null;
        if (node.isArray()) {
            rulesNode = node;
        } else if (node.isObject()) {
            rulesNode = Json.first(node, "rules", "conditions", "items", "list");
            otherNode = Json.first(node, "other", "otherwise", "default", "fallback");
        } else {
            throw new IllegalArgumentException("conditionConfig must be an object or an array");
        }

        List<ConditionRule> rules = new ArrayList<>();
        if (rulesNode != null && rulesNode.isArray()) {
            int idx = 0;
            for (JsonNode r : rulesNode) {
                idx++;
                if (r == null || !r.isObject()) {
                    throw new IllegalArgumentException("conditionConfig rule #" + idx + " must be an object");
                }
                String rawOp = Json.text(r, "op", "operator", "type", "cond");
                ConditionOp op = ConditionOp.parse(rawOp);
                if (op == null) {
                    throw new IllegalArgumentException("conditionConfig rule #" + idx
                            + " has unsupported operator: " + rawOp
                            + " (allowed: equals, not-equals, include, regex)");
                }
                JsonNode valueNode = Json.first(r, "value", "expect", "expected", "pattern", "keyword");
                String value = valueNode == null ? "" : (valueNode.isTextual() ? valueNode.asText() : valueNode.asText(""));
                String rawStatus = Json.text(r, "status", "result", "then", "state");
                ExecutionStatus status = ExecutionStatus.fromJudgeValue(rawStatus);
                if (status == null) {
                    throw new IllegalArgumentException("conditionConfig rule #" + idx
                            + " has illegal status: " + rawStatus
                            + " (allowed: pass, fail, block, exception)");
                }
                ConditionRule rule = new ConditionRule(op, value, status);
                if (!rule.isUsable()) {
                    throw new IllegalArgumentException("conditionConfig rule #" + idx
                            + " has an invalid regex: " + value);
                }
                rules.add(rule);
            }
        } else if (rulesNode != null && !rulesNode.isNull()) {
            throw new IllegalArgumentException("conditionConfig.rules must be an array");
        }

        ExecutionStatus other = null;
        if (otherNode != null && !otherNode.isNull()) {
            String rawOther = otherNode.isObject()
                    ? Json.text(otherNode, "status", "result", "then", "state")
                    : otherNode.asText(null);
            if (rawOther != null && !rawOther.isBlank()) {
                other = ExecutionStatus.fromJudgeValue(rawOther);
                if (other == null) {
                    throw new IllegalArgumentException("conditionConfig.other has illegal status: " + rawOther
                            + " (allowed: pass, fail, block, exception)");
                }
            }
        }
        return new ConditionConfig(rules, other);
    }
}
