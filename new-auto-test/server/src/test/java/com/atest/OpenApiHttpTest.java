package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.atest.domain.AgentEntity;
import com.atest.repo.AgentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The open-API wire surface: JSON bodies, HTTP status codes and the requestId query. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-openapi-http;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class OpenApiHttpTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private AgentRepository agentRepository;

    @BeforeEach
    void seedAgent() {
        if (agentRepository.findById("http-agent").isEmpty()) {
            AgentEntity agent = new AgentEntity();
            agent.setAgentId("http-agent");
            agent.setDisplayTag("http-agent");
            agent.setCreatedAt(Instant.now());
            agent.setUpdatedAt(Instant.now());
            agentRepository.save(agent);
        }
    }

    private ResponseEntity<JsonNode> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    void batchCreateThenQueryByRequestIdOverHttp() {
        ResponseEntity<JsonNode> created = postJson("/api/tasks/batch", """
                {"requestId":"http.batch-1","callbackUrl":"http://127.0.0.1:1/cb","items":[
                  {"command":"echo a","targets":["http-agent"],"timeoutSec":60},
                  {"command":"echo b","targets":["http-agent"],"name":"第二条"}
                ]}""");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().get("requestId").asText()).isEqualTo("http.batch-1");
        assertThat(created.getBody().get("tasks")).hasSize(2);

        // duplicate requestId -> 409 over the wire
        ResponseEntity<JsonNode> dup = postJson("/api/tasks", """
                {"requestId":"http.batch-1","command":"echo dup","targets":["http-agent"]}""");
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("code").asText()).isEqualTo("conflict");

        // unknown target rejects the whole batch with 400
        ResponseEntity<JsonNode> bad = postJson("/api/tasks/batch", """
                {"requestId":"http.batch-2","items":[
                  {"command":"echo a","targets":["http-agent"]},
                  {"command":"echo b","targets":["ghost-agent"]}
                ]}""");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("message").asText()).contains("items[1]");

        // open query: every task of the batch, with executions and callback fields
        ResponseEntity<JsonNode> query = rest.getForEntity("/api/tasks?requestId=http.batch-1", JsonNode.class);
        assertThat(query.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode items = query.getBody().get("items");
        assertThat(items).hasSize(2);
        for (JsonNode item : items) {
            assertThat(item.get("requestId").asText()).isEqualTo("http.batch-1");
            assertThat(item.get("callbackStatus").asText()).isEqualTo("pending");
            assertThat(item.get("executions")).hasSize(1);
        }
        assertThat(query.getBody().get("items").get(1).get("name").asText()).isEqualTo("第二条");

        // nothing under the rejected batch id
        ResponseEntity<JsonNode> empty = rest.getForEntity("/api/tasks?requestId=http.batch-2", JsonNode.class);
        assertThat(empty.getBody().get("items")).isEmpty();
    }
}
