package com.atest.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A request for a path that simply does not exist must come back 404, not 500.
 *
 * Live repro (2026-08-27): GET /api/events answered
 * {"code":"internal_error",...,"status":500} and the server logged a full ERROR stack trace,
 * because the catch-all {@code @ExceptionHandler(Exception.class)} swallowed Spring's
 * {@code NoResourceFoundException} (thrown for unmatched paths since Spring 6.1) and turned
 * the routine 404 into an internal error.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-unknown-path;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class UnknownPathHttpStatusTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void unknownApiPathIsNotFoundNotInternalError() {
        ResponseEntity<JsonNode> rsp = rest.getForEntity("/api/does-not-exist", JsonNode.class);
        assertThat(rsp.getStatusCode())
                .as("an unmatched path is a client-side 404, never an internal error")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rsp.getBody().get("code").asText()).isEqualTo("not_found");
    }
}
