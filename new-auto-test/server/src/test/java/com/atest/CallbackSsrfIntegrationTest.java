package com.atest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.atest.common.ApiException;
import com.atest.common.Json;
import com.atest.domain.AgentEntity;
import com.atest.domain.CallbackStatus;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.AgentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.service.ExecutionService;
import com.atest.service.TaskService;
import com.atest.web.dto.CreateTaskRequest;
import com.atest.web.dto.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * SSRF policy wired through the real create → terminal → callback flow: ingest rejects
 * out-of-policy URLs with 400, an allowlisted loopback receiver still gets its callback, and a
 * URL that turns bad AFTER create (DNS rebinding stand-in: direct DB edit) is caught by the
 * send-time re-check and marks the callback failed without any HTTP attempt.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-ssrf;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0",
        "atest.callback.allowed-hosts=127.0.0.1",
        "atest.callback.backoff-base-ms=5",
        "atest.callback.timeout-ms=2000"
})
class CallbackSsrfIntegrationTest {

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
    private static final AtomicInteger hits = new AtomicInteger();

    @BeforeAll
    static void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/cb", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop(0);
    }

    @BeforeEach
    void seedAgent() {
        if (agentRepository.findById("ssrf-agent").isEmpty()) {
            AgentEntity agent = new AgentEntity();
            agent.setAgentId("ssrf-agent");
            agent.setDisplayTag("ssrf-agent");
            agent.setCreatedAt(Instant.now());
            agent.setUpdatedAt(Instant.now());
            agentRepository.save(agent);
        }
    }

    @Test
    void ingestRejectsUrlOutsideTheAllowlistWith400() {
        for (String url : new String[]{
                "http://10.1.2.3/cb",                       // private, not allowlisted
                "http://169.254.169.254/latest/meta-data/", // metadata endpoint
                "http://93.184.216.34/cb"}) {               // public but not in the allowlist
            assertThatThrownBy(() -> taskService.create(request(url)))
                    .as(url)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getMessage()).contains("allowed-hosts");
                    });
        }
    }

    @Test
    void allowlistedLoopbackReceiverStillGetsTheCallback() {
        hits.set(0);
        TaskView task = taskService.create(request(
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/cb"));
        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        executionService.finish(exec, ExecutionStatus.PASS, "junit", null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(taskRepository.findById(task.id()).orElseThrow().getCallbackStatus())
                        .isEqualTo(CallbackStatus.SUCCESS));
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void urlTurningForbiddenAfterCreateIsCaughtAtSendTimeAndFailsHard() {
        TaskView task = taskService.create(request(
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/cb"));
        // stand-in for DNS rebinding: the stored URL now points at a forbidden target
        TaskEntity entity = taskRepository.findById(task.id()).orElseThrow();
        entity.setCallbackUrl("http://169.254.169.254/latest/meta-data/");
        taskRepository.save(entity);

        int before = hits.get();
        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        executionService.finish(exec, ExecutionStatus.PASS, "junit", null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            TaskEntity reloaded = taskRepository.findById(task.id()).orElseThrow();
            assertThat(reloaded.getCallbackStatus()).isEqualTo(CallbackStatus.FAILED);
            assertThat(reloaded.getCallbackLastError()).contains("安全策略");
        });
        // hard failure on the first attempt: no delivery, no retries
        assertThat(taskRepository.findById(task.id()).orElseThrow().getCallbackAttempts()).isEqualTo(1);
        assertThat(hits.get()).isEqualTo(before);
    }

    private CreateTaskRequest request(String callbackUrl) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setCommand("echo ssrf");
        request.setOperator("junit");
        request.setCallbackUrl(callbackUrl);
        request.setTargets(List.of((JsonNode) Json.mapper().getNodeFactory().textNode("ssrf-agent")));
        return request;
    }
}
