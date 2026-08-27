package com.atest.judge;

import java.util.List;

import com.atest.domain.ExecutionStatus;
import org.springframework.stereotype.Service;

/**
 * Verdict rules (docs/protocol.md):
 * <ul>
 *   <li>the judged text is always the last line of output</li>
 *   <li>no conditionConfig: exitCode == 0 -&gt; pass, else fail</li>
 *   <li>with conditionConfig: first matching rule wins</li>
 *   <li>nothing matched: use "other" when present, else lastLine == "0" -&gt; pass, else fail</li>
 * </ul>
 * Only pass/fail/block/exception can ever come out of here.
 */
@Service
public class JudgeService {

    public JudgeResult judge(String lastLine, Integer exitCode, String conditionConfigJson) {
        ConditionConfig config;
        try {
            config = ConditionConfig.parse(conditionConfigJson);
        } catch (IllegalArgumentException e) {
            return JudgeResult.of(ExecutionStatus.EXCEPTION,
                    "conditionConfig 非法: " + e.getMessage());
        }
        return judge(lastLine, exitCode, config);
    }

    public JudgeResult judge(String lastLine, Integer exitCode, ConditionConfig config) {
        String line = normalizeLine(lastLine);

        if (config == null || config.isEmpty()) {
            return byExitCode(exitCode);
        }

        int idx = 0;
        for (ConditionRule rule : config.getRules()) {
            idx++;
            if (!rule.isUsable()) {
                continue;
            }
            if (rule.matches(line)) {
                return JudgeResult.of(rule.getStatus(),
                        "命中规则 #" + idx + " " + rule.describe(),
                        rule.describe());
            }
        }

        if (config.getOther() != null) {
            return JudgeResult.of(config.getOther(), "未命中规则，使用 other=" + config.getOther().wire());
        }

        if ("0".equals(line)) {
            return JudgeResult.of(ExecutionStatus.PASS, "未命中规则且无 other，最后一行为 \"0\"");
        }
        return JudgeResult.of(ExecutionStatus.FAIL, "未命中规则且无 other，最后一行不是 \"0\"");
    }

    private JudgeResult byExitCode(Integer exitCode) {
        if (exitCode != null && exitCode == 0) {
            return JudgeResult.of(ExecutionStatus.PASS, "无 conditionConfig，exitCode=0");
        }
        return JudgeResult.of(ExecutionStatus.FAIL, "无 conditionConfig，exitCode=" + exitCode);
    }

    /** Trailing blank lines from a final newline must not hide the real last line. */
    public static String normalizeLine(String raw) {
        if (raw == null) {
            return "";
        }
        String[] parts = raw.split("\r?\n", -1);
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].strip();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return "";
    }

    /** Picks the judged line out of a captured tail. */
    public static String lastLineOf(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String candidate = normalizeLine(lines.get(i));
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return "";
    }

    /** Validation hook for POST /api/tasks. */
    public void validate(String conditionConfigJson) {
        ConditionConfig.parse(conditionConfigJson);
    }
}
