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
import com.atest.domain.TaskStatus;
import com.atest.repo.AgentRepository;
import com.atest.repo.OpenRequestRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

/**
 * Open API surface: requestId uniqueness, per-item partial success on batch create, and the
 * one-shot result callback that fires when the TASK (not each execution) reaches a terminal state.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-openapi;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0",
        // the local test receiver is on loopback, which the SSRF policy blocks by default
        "atest.callback.allowed-hosts=127.0.0.1",
        // fast callback retries: waits are 5/10/20/40/80 ms instead of 1/2/4/8/16 s
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
    @Autowired
    private OpenRequestRepository openRequestRepository;

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
        assertThat(opsTask.requestId()).matches(TaskService.REQUEST_ID_PATTERN);
        assertThat(opsTask.callbackStatus()).isEqualTo("none");
    }

    @Test
    void requestIdIsAutoGeneratedWhenOmitted() {
        TaskView task = taskService.create(request(null, null, "echo auto", "open-agent-a"));
        // the minted key is returned on the TaskView and obeys the caller charset contract
        assertThat(task.requestId()).isNotBlank();
        assertThat(task.requestId()).matches(TaskService.REQUEST_ID_PATTERN);

        // it is queryable exactly like a caller-supplied one…
        @SuppressWarnings("unchecked")
        List<TaskView> found = (List<TaskView>) taskService.listByRequestId(task.requestId(), false).get("items");
        assertThat(found).extracting(TaskView::id).containsExactly(task.id());

        // …occupies the global namespace…
        assertThatThrownBy(() -> taskService.create(request(task.requestId(), null, "echo dup", "open-agent-a")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // …and every omitted-id create gets its own key
        TaskView second = taskService.create(request(null, null, "echo auto2", "open-agent-a"));
        assertThat(second.requestId()).isNotEqualTo(task.requestId());

        // rerun-as-new is a fresh create call, so it mints a fresh key too
        // (rerun of any kind requires the selected executions to be terminal first)
        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        executionService.finish(exec, ExecutionStatus.PASS, "junit", null);
        TaskView rerun = taskService.rerun(task.id(), null);
        assertThat(rerun.requestId()).matches(TaskService.REQUEST_ID_PATTERN);
        assertThat(rerun.requestId()).isNotEqualTo(task.requestId());
    }

    // ------------------------------------------------------------------ batch

    @Test
    void batchIsPartialSuccessPerItem() {
        // one good item, one unknown target, one empty command -> only the bad two are rejected
        Map<String, Object> result = taskService.createBatch(batch("req.batch-partial", null,
                item("echo good", "open-agent-a"),
                item("echo bad", "no-such-agent"),
                item("", "open-agent-a")));

        @SuppressWarnings("unchecked")
        List<TaskView> tasks = (List<TaskView>) result.get("tasks");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).command()).isEqualTo("echo good");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).get("index")).isEqualTo(1);
        assertThat((String) errors.get(0).get("message")).contains("no-such-agent");
        assertThat(errors.get(1).get("index")).isEqualTo(2);
        assertThat((String) errors.get(1).get("message")).contains("command");

        // the requestId is consumed by the successful item and finds exactly that task
        assertThat(taskRepository.existsByRequestId("req.batch-partial")).isTrue();
        @SuppressWarnings("unchecked")
        List<TaskView> found = (List<TaskView>) taskService.listByRequestId("req.batch-partial", false).get("items");
        assertThat(found).hasSize(1);
    }

    @Test
    void batchItemWithAnyBadTargetIsRejectedWhole() {
        // items[1] mixes a good and an unknown target: the whole ITEM dies, never a subset task
        Map<String, Object> result = taskService.createBatch(batch("req.batch-mixed-targets", null,
                item("echo solo", "open-agent-a"),
                item("echo mixed", "open-agent-b", "ghost-agent")));

        @SuppressWarnings("unchecked")
        List<TaskView> tasks = (List<TaskView>) result.get("tasks");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).targets()).containsExactly("open-agent-a");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("index")).isEqualTo(1);
        assertThat((String) errors.get(0).get("message")).contains("ghost-agent");

        // no task was created for the mixed item, not even for its valid target
        @SuppressWarnings("unchecked")
        List<TaskView> found = (List<TaskView>) taskService
                .listByRequestId("req.batch-mixed-targets", false).get("items");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).command()).isEqualTo("echo solo");
    }

    @Test
    void batchWithZeroValidItemsDoesNotConsumeTheRequestId() {
        long before = taskRepository.count();

        BatchCreateTaskRequest allBad = batch("req.batch-allbad", null,
                item("", "open-agent-a"),
                item("echo bad", "no-such-agent"));
        assertThatThrownBy(() -> taskService.createBatch(allBad))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> errors = (List<Map<String, Object>>) e.getExtra().get("errors");
                    assertThat(errors).hasSize(2);
                    assertThat(errors.get(0).get("index")).isEqualTo(0);
                    assertThat(errors.get(1).get("index")).isEqualTo(1);
                });

        // nothing persisted, the key stays free…
        assertThat(taskRepository.count()).isEqualTo(before);
        assertThat(taskRepository.existsByRequestId("req.batch-allbad")).isFalse();
        assertThat(openRequestRepository.existsById("req.batch-allbad")).isFalse();

        // …so the caller can fix the payload and retry with the SAME requestId
        Map<String, Object> retry = taskService.createBatch(batch("req.batch-allbad", null,
                item("echo fixed", "open-agent-a")));
        @SuppressWarnings("unchecked")
        List<TaskView> tasks = (List<TaskView>) retry.get("tasks");
        assertThat(tasks).hasSize(1);
        assertThat(taskRepository.existsByRequestId("req.batch-allbad")).isTrue();
        assertThat(openRequestRepository.existsById("req.batch-allbad")).isTrue();
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

        // payload carries the full task fields and per-execution results, never the raw logs
        JsonNode payload = Json.read(lastOkBody.get());
        assertThat(payload.get("taskId").asLong()).isEqualTo(task.id());
        assertThat(payload.get("requestId").asText()).isEqualTo("req.cb-once");
        assertThat(payload.get("status").asText()).isEqualTo("finished");
        assertThat(payload.get("command").asText()).isEqualTo("echo both");
        assertThat(payload.get("targets")).hasSize(2);
        assertThat(payload.get("executions")).hasSize(2);
        JsonNode first = payload.get("executions").get(0);
        assertThat(first.get("executeId").asText()).isNotBlank();
        assertThat(first.get("agentTag").asText()).isEqualTo("open-agent-a");
        assertThat(first.get("status").asText()).isEqualTo("pass");
    }

    @Test
    void callbackRetriesFiveTimesThenIsMarkedFailed() {
        failHits.set(0);
        TaskView task = taskService.create(request("req.cb-fail", callbackUrl("/fail"),
                "echo fail", "open-agent-a"));
        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        executionService.finish(exec, ExecutionStatus.PASS, "test", null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            TaskEntity reloaded = taskRepository.findById(task.id()).orElseThrow();
            assertThat(reloaded.getCallbackStatus()).isEqualTo(CallbackStatus.FAILED);
        });

        // 1 initial send + 5 backoff retries (1s/2s/4s/8s/16s in production) = 6 attempts total
        TaskEntity reloaded = taskRepository.findById(task.id()).orElseThrow();
        assertThat(reloaded.getCallbackAttempts()).isEqualTo(6);
        assertThat(reloaded.getCallbackLastError()).contains("HTTP 500");
        assertThat(failHits.get()).isEqualTo(6);

        // callback retry never re-runs the shell: the execution result is untouched
        TaskExecutionEntity execReloaded = executionRepository.findById(exec.getId()).orElseThrow();
        assertThat(execReloaded.getStatus()).isEqualTo(ExecutionStatus.PASS);
        assertThat(execReloaded.getAttempt()).isEqualTo(1);
    }

    /**
     * Crash recovery: a callback claimed (RUNNING) by a process that died can never finish on
     * its own — the retry chain lives only in that process's memory and the backlog sweep
     * selects PENDING rows only. The boot requeue must flip exactly the rows claimed before
     * boot back to PENDING, and never a row claimed after boot (that would double-send it).
     */
    @Test
    void bootRequeueResetsCallbacksStuckInRunning() {
        TaskView stuck = taskService.create(request("req.cb-stuck", callbackUrl("/ok"),
                "echo stuck", "open-agent-a"));
        TaskView fresh = taskService.create(request("req.cb-fresh", callbackUrl("/ok"),
                "echo fresh", "open-agent-a"));
        Instant bootTime = Instant.now();

        // the previous process claimed the callback (pending -> running) and died before sending
        TaskEntity stuckTask = taskRepository.findById(stuck.id()).orElseThrow();
        stuckTask.setStatus(TaskStatus.FINISHED);
        stuckTask.setCallbackStatus(CallbackStatus.RUNNING);
        stuckTask.setUpdatedAt(bootTime.minusSeconds(60));
        taskRepository.save(stuckTask);

        // claimed after this boot by the live process: must stay running
        TaskEntity freshTask = taskRepository.findById(fresh.id()).orElseThrow();
        freshTask.setStatus(TaskStatus.FINISHED);
        freshTask.setCallbackStatus(CallbackStatus.RUNNING);
        freshTask.setUpdatedAt(bootTime.plusSeconds(60));
        taskRepository.save(freshTask);

        assertThat(taskRepository.requeueStuckCallbacks(bootTime, Instant.now()))
                .isGreaterThanOrEqualTo(1);
        assertThat(taskRepository.findById(stuck.id()).orElseThrow().getCallbackStatus())
                .isEqualTo(CallbackStatus.PENDING);
        assertThat(taskRepository.findById(fresh.id()).orElseThrow().getCallbackStatus())
                .isEqualTo(CallbackStatus.RUNNING);
        // the requeued row is what the periodic backlog sweep looks for
        assertThat(taskRepository.findCallbackBacklog(PageRequest.of(0, 50))).contains(stuck.id());

        // keep the sweep from actually delivering these into later tests of this class
        for (Long id : List.of(stuck.id(), fresh.id())) {
            TaskEntity t = taskRepository.findById(id).orElseThrow();
            t.setCallbackStatus(CallbackStatus.SUCCESS);
            taskRepository.save(t);
        }
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
