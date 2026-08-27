package com.atest.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DisplayTagsTest {

    @Test
    void acceptsInstallShCharset() {
        assertThat(DisplayTags.isValid("qa-node-01")).isTrue();
        assertThat(DisplayTags.isValid("build.01_x")).isTrue();
        assertThat(DisplayTags.isValid("a")).isTrue();
    }

    @Test
    void rejectsColonAtSpaceAndOverflow() {
        assertThat(DisplayTags.isValid("qa@node:1")).isFalse();
        assertThat(DisplayTags.isValid("has space")).isFalse();
        assertThat(DisplayTags.isValid("")).isFalse();
        assertThat(DisplayTags.isValid("x".repeat(65))).isFalse();
    }

    @Test
    void httpPathThrows400() {
        assertThatThrownBy(() -> DisplayTags.requireValidHttp("qa@node"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("displayTag");
        assertThat(DisplayTags.requireValidHttp(" docker-agent-01 ")).isEqualTo("docker-agent-01");
    }
}
