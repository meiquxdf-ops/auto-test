package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The documented local-dev opt-in (--spring.h2.console.enabled=true) still works. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-h2-on;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0",
        "spring.h2.console.enabled=true"
})
class H2ConsoleExplicitEnableTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void consoleComesBackWhenExplicitlyEnabled() {
        // the console servlet answers itself (login page after redirect), not the API's 404 JSON
        ResponseEntity<String> rsp = rest.getForEntity("/h2-console", String.class);
        assertThat(rsp.getStatusCode().is2xxSuccessful() || rsp.getStatusCode().is3xxRedirection())
                .as("expected the H2 console, got HTTP %s", rsp.getStatusCode())
                .isTrue();
    }
}
