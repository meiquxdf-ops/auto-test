package com.atest.judge;

import java.util.Locale;

public enum ConditionOp {
    EQUALS("equals"),
    NOT_EQUALS("not-equals"),
    INCLUDE("include"),
    REGEX("regex");

    private final String wire;

    ConditionOp(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static ConditionOp parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(" ", "");
        switch (s) {
            case "equals":
            case "eq":
            case "==":
                return EQUALS;
            case "not-equals":
            case "notequals":
            case "ne":
            case "!=":
                return NOT_EQUALS;
            case "include":
            case "includes":
            case "contains":
                return INCLUDE;
            case "regex":
            case "regexp":
            case "match":
                return REGEX;
            default:
                return null;
        }
    }
}
