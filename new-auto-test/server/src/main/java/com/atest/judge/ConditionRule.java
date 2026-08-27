package com.atest.judge;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.atest.domain.ExecutionStatus;

/** One conditionConfig row: operator + expected value -> execution status. */
public final class ConditionRule {

    private final ConditionOp op;
    private final String value;
    private final ExecutionStatus status;
    private final Pattern pattern;

    public ConditionRule(ConditionOp op, String value, ExecutionStatus status) {
        this.op = op;
        this.value = value == null ? "" : value;
        this.status = status;
        Pattern compiled = null;
        if (op == ConditionOp.REGEX) {
            try {
                compiled = Pattern.compile(this.value);
            } catch (PatternSyntaxException e) {
                compiled = null;
            }
        }
        this.pattern = compiled;
    }

    public ConditionOp getOp() {
        return op;
    }

    public String getValue() {
        return value;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    /** A regex rule whose pattern does not compile can never match. */
    public boolean isUsable() {
        return op != ConditionOp.REGEX || pattern != null;
    }

    public boolean matches(String lastLine) {
        String line = lastLine == null ? "" : lastLine;
        switch (op) {
            case EQUALS:
                return line.equals(value.trim()) || line.equals(value);
            case NOT_EQUALS:
                return !(line.equals(value.trim()) || line.equals(value));
            case INCLUDE:
                return line.contains(value);
            case REGEX:
                return pattern != null && pattern.matcher(line).find();
            default:
                return false;
        }
    }

    public String describe() {
        return op.wire() + "(" + value + ")->" + status.wire();
    }
}
