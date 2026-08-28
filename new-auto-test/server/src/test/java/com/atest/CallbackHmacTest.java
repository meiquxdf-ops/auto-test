package com.atest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.atest.common.Json;
import com.atest.domain.AgentEntity;
import com.atest.domain.CallbackStatus;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.AgentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.service.ExecutionService;
import com.atest.service.TaskService;
import com.atest.web.dto.CreateTaskRequest;
import com.atest.web.dto.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Optional callback signing: with atest.callback.hmac-secret set, every callback POST carries
 * an HMAC-SHA256 of the exact body in X-Atest-Signature (hex) and X-Hub-Signature-256
 * (GitHub style, "sha256=hex"). The receiver recomputes the digest over the received bytes
 * with the shared secret — this test does exactly that with a known secret.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-hmac;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0",
        "atest.callback.hmac-secret=chaos-shared-secret-123",
        // the SSRF policy blocks loopback by default; the local test receiver must be allowlisted
        "atest.callback.allowed-hosts=127.0.0.1",
        "atest.callback.backoff-base-ms=5",
        "atest.callback.timeout-ms=2000"
})
class CallbackHmacTest {

    private static final String SECRET = "chaos-shared-secret-123";

    @Autowired
    private TaskService taskService;
    @Autowired
    private ExecutionService executionService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskExecutionRepository executionRepository;
    @Autowired
    private AgentRepository agentRepository;

    private static HttpServer receiver;
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<Headers> lastHeaders = new AtomicReference<>();

    @BeforeAll
    static void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/cb", exchange -> {
            lastBody.set(readBody(exchange.getRequestBody()));
            lastHeaders.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop(0);
    }

    private static String readBody(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void seedAgent() {
        if (agentRepository.findById("hmac-agent").isEmpty()) {
            AgentEntity agent = new AgentEntity();
            agent.setAgentId("hmac-agent");
            agent.setDisplayTag("hmac-agent");
            agent.setCreatedAt(Instant.now());
            agent.setUpdatedAt(Instant.now());
            agentRepository.save(agent);
        }
    }

    @Test
    void callbackCarriesVerifiableHmacSignatureHeaders() throws Exception {
        lastBody.set(null);
        lastHeaders.set(null);

        CreateTaskRequest request = new CreateTaskRequest();
        request.setCommand("echo signed");
        request.setOperator("junit");
        request.setRequestId("req.hmac-1");
        request.setCallbackUrl("http://127.0.0.1:" + receiver.getAddress().getPort() + "/cb");
        request.setTargets(List.of((JsonNode) Json.mapper().getNodeFactory().textNode("hmac-agent")));
        TaskView task = taskService.create(request);

        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        executionService.finish(exec, ExecutionStatus.PASS, "junit", null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(taskRepository.findById(task.id()).orElseThrow().getCallbackStatus())
                        .isEqualTo(CallbackStatus.SUCCESS));

        String body = lastBody.get();
        Headers headers = lastHeaders.get();
        assertThat(body).isNotNull();
        assertThat(headers).isNotNull();

        // the receiver-side verification: recompute HMAC-SHA256 over the received bytes
        String expected = hmac(SECRET, body);
        assertThat(headers.getFirst("X-Atest-Signature")).isEqualTo(expected);
        assertThat(headers.getFirst("X-Hub-Signature-256")).isEqualTo("sha256=" + expected);

        // a wrong secret must NOT verify — guards against a digest of something else entirely
        assertThat(headers.getFirst("X-Atest-Signature")).isNotEqualTo(hmac("wrong-secret", body));
        // and the signature covers the body, not e.g. the URL: flipping one byte breaks it
        assertThat(hmac(SECRET, body + " ")).isNotEqualTo(expected);

        // the signed payload is the real callback JSON
        JsonNode payload = Json.read(body);
        assertThat(payload.get("requestId").asText()).isEqualTo("req.hmac-1");
        assertThat(payload.get("status").asText()).isEqualTo("finished");
    }

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        // constant-time comparison is the receiver's job; assertEquals is fine in a test
        return HexFormat.of().formatHex(digest);
    }
}
