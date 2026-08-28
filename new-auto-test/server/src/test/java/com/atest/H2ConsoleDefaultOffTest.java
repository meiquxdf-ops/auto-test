package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The H2 web console (sa / blank password, arbitrary SQL) must not ship enabled: it rides the
 * same :8080 as the open API, so a default-on console hands the whole database to anyone who
 * can reach the server. Default = 404 like any unknown path; local debugging opts in with
 * --spring.h2.console.enabled=true (see application.yaml).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-h2-off;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class H2ConsoleDefaultOffTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void h2ConsoleIsNotServedByDefault() {
        ResponseEntity<JsonNode> rsp = rest.getForEntity("/h2-console", JsonNode.class);
        assertThat(rsp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rsp.getBody().get("code").asText()).isEqualTo("not_found");

        // deep console paths are gone too, not just the landing page
        ResponseEntity<String> login = rest.getForEntity("/h2-console/login.do", String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
