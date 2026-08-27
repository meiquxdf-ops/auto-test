package com.atest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import com.atest.web.dto.BatchCreateTaskRequest;
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
 * Open API surface: requestId uniqueness, all-or-nothing batch create, and the one-shot result
 * callback that fires when the TASK (not each execution) reaches a terminal state.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-openapi;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0",
        // fast callback retries: waits are 5/10/20/40 ms instead of 1/2/4/8 s
        "atest.callback.backoff-base-ms=5",
        "atest.callback.timeout-ms=2000"
})
class OpenApiCallbackTest {

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

    private static HttpServer callbackServer;
    private static final AtomicInteger okHits = new AtomicInteger();
    private static final AtomicInteger failHits = new AtomicInteger();
    private static final AtomicReference<String> lastOkBody = new AtomicReference<>();

    @BeforeAll
    static void startCallbackServer() throws IOException {
        callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        callbackServer.createContext("/ok", exchange -> {
            lastOkBody.set(readBody(exchange.getRequestBody()));
            okHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        callbackServer.createContext("/fail", exchange -> {
            readBody(exchange.getRequestBody());
            failHits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        callbackServer.start();
    }

    @AfterAll
    static void stopCallbackServer() {
        callbackServer.stop(0);
    }

    private static String readBody(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String callbackUrl(String path) {
        return "http://127.0.0.1:" + callbackServer.getAddress().getPort() + path;
    }

    @BeforeEach
    void seedAgents() {
        for (String name : new String[]{"open-agent-a", "open-agent-b"}) {
            if (agentRepository.findById(name).isEmpty()) {
                AgentEntity agent = new AgentEntity();
                agent.setAgentId(name);
                agent.setDisplayTag(name);
                agent.setCreatedAt(Instant.now());
                agent.setUpdatedAt(Instant.now());
                agentRepository.save(agent);
            }
        }
    }

    // ------------------------------------------------------------- requestId

    @Test
    void duplicateRequestIdIsRejectedWithConflict() {
        taskService.create(request("req.dup-1", null, "echo one", "open-agent-a"));

        assertThatThrownBy(() -> taskService.create(request("req.dup-1", null, "echo two", "open-agent-a")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // the same key also blocks a batch create
        BatchCreateTaskRequest batch = batch("req.dup-1", null, item("echo x", "open-agent-a"));
        assertThatThrownBy(() -> taskService.createBatch(batch))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void malformedRequestIdOrCallbackUrlIsRejected() {
        assertThatThrownBy(() -> taskService.create(request("bad id with spaces", null, "echo x", "open-agent-a")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> taskService.create(request("req.cb-bad", "ftp://x/y", "echo x", "open-agent-a")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        // batch requires a requestId, single create does not
        assertThatThrownBy(() -> taskService.createBatch(batch(" ", null, item("echo x", "open-agent-a"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        TaskView opsTask = taskService.create(request(null, null, "echo ops", "open-agent-a"));
        assertThat(opsTask.requestId()).isNull();
        assertThat(opsTask.callbackStatus()).isEqualTo("none");
    }

    // ------------------------------------------------------------------ batch

    @Test
    void batchWithUnknownTargetRejectsTheWholeRequest() {
        long before = taskRepository.count();

        BatchCreateTaskRequest batch = batch("req.batch-bad", null,
                item("echo good", "open-agent-a"),
                item("echo bad", "no-such-agent"));
        assertThatThrownBy(() -> taskService.createBatch(batch))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("items[1]").contains("no-such-agent");
                });

        // no partial create: nothing under the requestId, no extra tasks at all
        assertThat(taskRepository.count()).isEqualTo(before);
        assertThat(taskRepository.existsByRequestId("req.batch-bad")).isFalse();
    }

    @Test
    void batchCreatesTasksGroupedByOneRequestIdAndQueryFindsThem() {
        Map<String, Object> result = taskService.createBatch(batch("req.batch-ok", callbackUrl("/ok-unused"),
                item("echo alpha", "open-agent-a"),
                item("echo beta", "open-agent-b")));

        assertThat(result.get("requestId")).isEqualTo("req.batch-ok");
        @SuppressWarnings("unchecked")
        List<TaskView> tasks = (List<TaskView>) result.get("tasks");
        assertThat(tasks).hasSize(2);
        assertThat(tasks).allSatisfy(t -> {
            assertThat(t.requestId()).isEqualTo("req.batch-ok");
            assertThat(t.callbackStatus()).isEqualTo("pending");
        });
        assertThat(tasks.get(0).command()).isEqualTo("echo alpha");
        assertThat(tasks.get(1).command()).isEqualTo("echo beta");

        Map<String, Object> query = taskService.listByRequestId("req.batch-ok", true);
        @SuppressWarnings("unchecked")
        List<TaskView> found = (List<TaskView>) query.get("items");
        assertThat(found).hasSize(2);
        assertThat(found.get(0).executions()).hasSize(1);
    }

    // --------------------------------------------------------------- callback

    @Test
    void callbackFiresOnceWhenTheTaskIsTerminalNotPerExecution() throws Exception {
        okHits.set(0);
        TaskView task = taskService.create(request("req.cb-once", callbackUrl("/ok"),
                "echo both", "open-agent-a", "open-agent-b"));
        List<TaskExecutionEntity> executions = executionRepository.findByTaskIdOrderByIdAsc(task.id());
        assertThat(executions).hasSize(2);

        // first execution done, second still pending -> task is NOT terminal, nothing may fire
        executionService.finish(executions.get(0), ExecutionStatus.PASS, "test", null);
        Thread.sleep(300);
        assertThat(okHits.get()).isZero();
        assertThat(taskRepository.findById(task.id()).orElseThrow().getCallbackStatus())
                .isEqualTo(CallbackStatus.PENDING);

        // second execution done -> task finished -> exactly one callback
        executionService.finish(executions.get(1), ExecutionStatus.FAIL, "test", null);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            TaskEntity reloaded = taskRepository.findById(task.id()).orElseThrow();
            assertThat(reloaded.getCallbackStatus()).isEqualTo(CallbackStatus.SUCCESS);
        });
        Thread.sleep(300);
        assertThat(okHits.get()).isEqualTo(1);

        TaskEntity reloaded = taskRepository.findById(task.id()).orElseThrow();
        assertThat(reloaded.getCallbackAttempts()).isEqualTo(1);
        assertThat(reloaded.getCallbackLastError()).isNull();
        assertThat(reloaded.getCallbackLastAt()).isNotNull();

        // payload carries the useful result fields, never the raw logs
        JsonNode payload = Json.read(lastOkBody.get());
        assertThat(payload.get("taskId").asLong()).isEqualTo(task.id());
        assertThat(payload.get("requestId").asText()).isEqualTo("req.cb-once");
        assertThat(payload.get("status").asText()).isEqualTo("finished");
        assertThat(payload.get("executions")).hasSize(2);
        JsonNode first = payload.get("executions").get(0);
        assertThat(first.get("executeId").asText()).isNotBlank();
        assertThat(first.get("agentTag").asText()).isEqualTo("open-agent-a");
        assertThat(first.get("status").asText()).isEqualTo("pass");
    }

    @Test
    void callbackRetriesAndIsMarkedFailedAfterFiveAttempts() {
        failHits.set(0);
        TaskView task = taskService.create(request("req.cb-fail", callbackUrl("/fail"),
                "echo fail", "open-agent-a"));
        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        executionService.finish(exec, ExecutionStatus.PASS, "test", null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            TaskEntity reloaded = taskRepository.findById(task.id()).orElseThrow();
            assertThat(reloaded.getCallbackStatus()).isEqualTo(CallbackStatus.FAILED);
        });

        TaskEntity reloaded = taskRepository.findById(task.id()).orElseThrow();
        assertThat(reloaded.getCallbackAttempts()).isEqualTo(5);
        assertThat(reloaded.getCallbackLastError()).contains("HTTP 500");
        assertThat(failHits.get()).isEqualTo(5);

        // callback retry never re-runs the shell: the execution result is untouched
        TaskExecutionEntity execReloaded = executionRepository.findById(exec.getId()).orElseThrow();
        assertThat(execReloaded.getStatus()).isEqualTo(ExecutionStatus.PASS);
        assertThat(execReloaded.getAttempt()).isEqualTo(1);
    }

    @Test
    void cancelAlsoFiresTheCallback() {
        okHits.set(0);
        TaskView task = taskService.create(request("req.cb-cancel", callbackUrl("/ok"),
                "echo cancel", "open-agent-a"));
        taskService.cancel(task.id(), "junit");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            TaskEntity reloaded = taskRepository.findById(task.id()).orElseThrow();
            assertThat(reloaded.getCallbackStatus()).isEqualTo(CallbackStatus.SUCCESS);
        });
        JsonNode payload = Json.read(lastOkBody.get());
        assertThat(payload.get("status").asText()).isEqualTo("canceled");
    }

    // ---------------------------------------------------------------- helpers

    private CreateTaskRequest request(String requestId, String callbackUrl, String command, String... targets) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setCommand(command);
        request.setOperator("junit");
        request.setRequestId(requestId);
        request.setCallbackUrl(callbackUrl);
        request.setTargets(targetNodes(targets));
        return request;
    }

    private BatchCreateTaskRequest batch(String requestId, String callbackUrl,
                                         BatchCreateTaskRequest.Item... items) {
        BatchCreateTaskRequest request = new BatchCreateTaskRequest();
        request.setRequestId(requestId);
        request.setCallbackUrl(callbackUrl);
        request.setItems(List.of(items));
        return request;
    }

    private BatchCreateTaskRequest.Item item(String command, String... targets) {
        BatchCreateTaskRequest.Item item = new BatchCreateTaskRequest.Item();
        item.setCommand(command);
        item.setOperator("junit");
        item.setTargets(targetNodes(targets));
        return item;
    }

    private List<JsonNode> targetNodes(String... targets) {
        return List.of(targets).stream()
                .map(t -> (JsonNode) Json.mapper().getNodeFactory().textNode(t))
                .toList();
    }
}
