package com.atest.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atest.domain.ExecutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JudgeServiceTest {

    private final JudgeService judge = new JudgeService();

    // ------------------------------------------------------------ no config

    @Test
    @DisplayName("no conditionConfig: exitCode 0 passes, anything else fails")
    void noConfigUsesExitCode() {
        assertThat(judge.judge("whatever", 0, (String) null).status()).isEqualTo(ExecutionStatus.PASS);
        assertThat(judge.judge("whatever", 1, (String) null).status()).isEqualTo(ExecutionStatus.FAIL);
        assertThat(judge.judge("whatever", 137, (String) null).status()).isEqualTo(ExecutionStatus.FAIL);
        assertThat(judge.judge("whatever", null, (String) null).status()).isEqualTo(ExecutionStatus.FAIL);
        assertThat(judge.judge(null, 0, "   ").status()).isEqualTo(ExecutionStatus.PASS);
        // an empty object is not a config either, so the exit code still decides
        assertThat(judge.judge("0", 3, "{}").status()).isEqualTo(ExecutionStatus.FAIL);
    }

    // ------------------------------------------------------------- operators

    @Test
    void equalsOperator() {
        String cfg = """
                {"rules":[{"op":"equals","value":"OK","status":"pass"}]}""";
        assertThat(judge.judge("OK", 1, cfg).status()).isEqualTo(ExecutionStatus.PASS);
        assertThat(judge.judge("ok", 1, cfg).status()).isEqualTo(ExecutionStatus.FAIL);
        assertThat(judge.judge("NOT OK", 0, cfg).status()).isEqualTo(ExecutionStatus.FAIL);
    }

    @Test
    void notEqualsOperator() {
        String cfg = """
                {"rules":[{"op":"not-equals","value":"0","status":"fail"}],"other":"pass"}""";
        assertThat(judge.judge("1", 0, cfg).status()).isEqualTo(ExecutionStatus.FAIL);
        assertThat(judge.judge("0", 1, cfg).status()).isEqualTo(ExecutionStatus.PASS);
    }

    @Test
    void includeOperator() {
        String cfg = """
                {"rules":[{"op":"include","value":"ERROR","status":"fail"}],"other":"pass"}""";
        assertThat(judge.judge("build finished with ERROR count=2", 0, cfg).status())
                .isEqualTo(ExecutionStatus.FAIL);
        assertThat(judge.judge("build finished", 0, cfg).status()).isEqualTo(ExecutionStatus.PASS);
        assertThat(judge.judge("error lower case", 0, cfg).status()).isEqualTo(ExecutionStatus.PASS);
    }

    @Test
    void regexOperator() {
        String cfg = """
                {"rules":[{"op":"regex","value":"^FAILED:\\\\s*\\\\d+","status":"block"}],"other":"pass"}""";
        assertThat(judge.judge("FAILED: 12", 0, cfg).status()).isEqualTo(ExecutionStatus.BLOCK);
        assertThat(judge.judge("PASSED: 12", 0, cfg).status()).isEqualTo(ExecutionStatus.PASS);
    }

    @Test
    @DisplayName("regex matches a substring, not only the whole line")
    void regexIsFindNotFullMatch() {
        String cfg = """
                {"rules":[{"op":"regex","value":"timeout","status":"exception"}],"other":"pass"}""";
        assertThat(judge.judge("job hit a timeout while waiting", 0, cfg).status())
                .isEqualTo(ExecutionStatus.EXCEPTION);
    }

    // ------------------------------------------------------------- ordering

    @Test
    @DisplayName("first matching rule wins")
    void firstMatchWins() {
        String cfg = """
                {"rules":[
                  {"op":"include","value":"WARN","status":"block"},
                  {"op":"include","value":"WARNING","status":"fail"}
                ],"other":"pass"}""";
        JudgeResult result = judge.judge("WARNING: disk almost full", 0, cfg);
        assertThat(result.status()).isEqualTo(ExecutionStatus.BLOCK);
        assertThat(result.matchedRule()).contains("include(WARN)");
    }

    // ---------------------------------------------------------------- other

    @Test
    void otherIsUsedWhenNothingMatches() {
        String cfg = """
                {"rules":[{"op":"equals","value":"OK","status":"pass"}],"other":"block"}""";
        assertThat(judge.judge("nope", 0, cfg).status()).isEqualTo(ExecutionStatus.BLOCK);
        assertThat(judge.judge("nope", 1, cfg).status()).isEqualTo(ExecutionStatus.BLOCK);
    }

    @Test
    @DisplayName("no match and no other: last line \"0\" passes, else fails")
    void fallbackWithoutOther() {
        String cfg = """
                {"rules":[{"op":"equals","value":"OK","status":"pass"}]}""";
        assertThat(judge.judge("0", 1, cfg).status()).isEqualTo(ExecutionStatus.PASS);
        assertThat(judge.judge("1", 0, cfg).status()).isEqualTo(ExecutionStatus.FAIL);
        assertThat(judge.judge("", 0, cfg).status()).isEqualTo(ExecutionStatus.FAIL);
        assertThat(judge.judge(null, 0, cfg).status()).isEqualTo(ExecutionStatus.FAIL);
    }

    @Test
    @DisplayName("the exit code is ignored as soon as a conditionConfig exists")
    void exitCodeIgnoredWithConfig() {
        String cfg = """
                {"rules":[{"op":"equals","value":"DONE","status":"pass"}],"other":"fail"}""";
        assertThat(judge.judge("DONE", 255, cfg).status()).isEqualTo(ExecutionStatus.PASS);
    }

    // ------------------------------------------------------------ last line

    @Test
    @DisplayName("only the last non blank line is judged")
    void lastLineHandling() {
        assertThat(JudgeService.normalizeLine("a\nb\nc")).isEqualTo("c");
        assertThat(JudgeService.normalizeLine("a\nb\nc\n")).isEqualTo("c");
        assertThat(JudgeService.normalizeLine("a\r\nb\r\n")).isEqualTo("b");
        assertThat(JudgeService.normalizeLine("  0  ")).isEqualTo("0");
        assertThat(JudgeService.normalizeLine("\n\n")).isEmpty();
        assertThat(JudgeService.normalizeLine(null)).isEmpty();
        assertThat(JudgeService.lastLineOf(java.util.List.of("first", "0", "   "))).isEqualTo("0");
        assertThat(JudgeService.lastLineOf(java.util.List.of())).isEmpty();

        String cfg = """
                {"rules":[{"op":"equals","value":"0","status":"pass"}],"other":"fail"}""";
        assertThat(judge.judge("noise\n0\n", 1, cfg).status()).isEqualTo(ExecutionStatus.PASS);
        assertThat(judge.judge("0\nnoise", 0, cfg).status()).isEqualTo(ExecutionStatus.FAIL);
    }

    // -------------------------------------------------------------- parsing

    @Test
    @DisplayName("only pass/fail/block/exception may come out of a rule")
    void illegalStatusIsRejected() {
        assertThatThrownBy(() -> judge.validate("""
                {"rules":[{"op":"equals","value":"1","status":"running"}]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal status");

        assertThatThrownBy(() -> judge.validate("""
                {"rules":[{"op":"equals","value":"1","status":"pass"}],"other":"canceled"}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
    }

    @Test
    void unsupportedOperatorIsRejected() {
        assertThatThrownBy(() -> judge.validate("""
                {"rules":[{"op":"startsWith","value":"1","status":"pass"}]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported operator");
    }

    @Test
    void invalidRegexIsRejected() {
        assertThatThrownBy(() -> judge.validate("""
                {"rules":[{"op":"regex","value":"[unclosed","status":"fail"}]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid regex");
    }

    @Test
    @DisplayName("a broken config at judge time degrades to exception instead of a wrong verdict")
    void brokenConfigAtJudgeTime() {
        JudgeResult result = judge.judge("0", 0, "{\"rules\":[{\"op\":\"nope\",\"status\":\"pass\"}]}");
        assertThat(result.status()).isEqualTo(ExecutionStatus.EXCEPTION);
    }

    @Test
    @DisplayName("operator aliases and a bare rule array are accepted")
    void flexibleParsing() {
        String bareArray = """
                [{"operator":"contains","expect":"OK","result":"pass"}]""";
        assertThat(judge.judge("all OK", 1, bareArray).status()).isEqualTo(ExecutionStatus.PASS);

        String aliases = """
                {"conditions":[{"type":"==","value":"9","then":"exception"}],"default":"pass"}""";
        assertThat(judge.judge("9", 0, aliases).status()).isEqualTo(ExecutionStatus.EXCEPTION);
        assertThat(judge.judge("8", 1, aliases).status()).isEqualTo(ExecutionStatus.PASS);
    }

    @Test
    void emptyRulesFallBackToExitCode() {
        assertThat(judge.judge("x", 0, "{\"rules\":[]}").status()).isEqualTo(ExecutionStatus.PASS);
        assertThat(judge.judge("x", 2, "{\"rules\":[]}").status()).isEqualTo(ExecutionStatus.FAIL);
        // an "other" alone still counts as a real config
        assertThat(judge.judge("x", 0, "{\"rules\":[],\"other\":\"block\"}").status())
                .isEqualTo(ExecutionStatus.BLOCK);
    }
}
