package com.atest.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The mysql profile must take its connection info from the environment
 * (MYSQL_HOST/PORT/DATABASE/USER/PASSWORD) — a hardcoded root/root once shipped in this file
 * and would go straight into every deployment and the git history. Plain-text guard: cheap,
 * and it fails loudly if someone puts a literal credential back.
 */
class MysqlProfileNoSecretsTest {

    @Test
    void mysqlProfileReadsCredentialsFromEnvPlaceholders() throws Exception {
        String yaml = new String(new ClassPathResource("application.yaml")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String mysqlDoc = yaml.substring(yaml.indexOf("on-profile: mysql"));

        assertThat(mysqlDoc).contains("${MYSQL_USER:");
        assertThat(mysqlDoc).contains("${MYSQL_PASSWORD:");
        assertThat(mysqlDoc).contains("${MYSQL_HOST:");
        // no literal credentials anywhere in the mysql document
        assertThat(mysqlDoc).doesNotContain("password: root");
        assertThat(mysqlDoc).doesNotContain("username: root");
    }
}
