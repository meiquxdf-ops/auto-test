package com.atest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.atest.common.Json;
import com.atest.domain.AgentEntity;
import com.atest.domain.AgentStatus;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.AgentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.service.AgentService;
import com.atest.service.DispatchService;
import com.atest.service.LogService;
import com.atest.service.TaskService;
import com.atest.tcp.AgentTcpServer;
import com.atest.tcp.Envelope;
import com.atest.tcp.ErrorCodes;
import com.atest.web.dto.CreateTaskRequest;
import com.atest.web.dto.PatchAgentRequest;
import com.atest.web.dto.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Drives a real TCP session against the running server: hello, exec ACK, log, fin, verdict. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-protocol;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0",
        "atest.dispatch.interval-ms=200",
        "atest.agent.dup-session-ping-timeout-ms=800"
})
class AgentProtocolIntegrationTest {

    @Autowired
    private AgentTcpServer tcpServer;
    @Autowired
    private TaskService taskService;
    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private TaskExecutionRepository executionRepository;
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private LogService logService;
    @Autowired
    private AgentService agentService;

    @Test
    void fullRunEndsInPassFromFin() throws Exception {
        try (TestAgent agent = new TestAgent(tcpServer.boundPort())) {
            Envelope hello = agent.hello("agent-pass", List.of());
            assertThat(hello.isOk()).isTrue();
            assertThat(hello.result().get("sessionId").asText()).isNotBlank();

            TaskView task = createTask("agent-pass", "echo hi", """
                    {"rules":[{"op":"equals","value":"0","status":"pass"}],"other":"fail"}""");
            dispatchService.dispatchOnce();

            Envelope exec = agent.readUntil("exec");
            String executeId = exec.args().get("executeId").asText();
            String token = exec.args().get("token").asText();
            assertThat(exec.args().get("command").asText()).isEqualTo("echo hi");
            agent.reply(exec.id, Map.of("accepted", true));

            Envelope logRsp = agent.request("log", Map.of(
                    "executeId", executeId, "token", token, "fromSeq", 1,
                    "lines", List.of("starting", "0")));
            assertThat(logRsp.result().get("ackSeq").asInt()).isEqualTo(2);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(statusOf(executeId)).isEqualTo(ExecutionStatus.RUNNING));

            Envelope finRsp = agent.request("fin", Map.of(
                    "executeId", executeId, "token", token, "exitCode", 0,
                    "lastLine", "0", "reason", "finished"));
            assertThat(finRsp.result().get("applied").asBoolean()).isTrue();

            assertThat(statusOf(executeId)).isEqualTo(ExecutionStatus.PASS);
            TaskExecutionEntity finished = executionRepository.findByExecuteId(executeId).orElseThrow();
            assertThat(finished.getExitCode()).isZero();
            assertThat(finished.getLastLine()).isEqualTo("0");
            assertThat(logService.page(finished, 0, 100).lines()).hasSize(2);
            assertThat(task.status()).isEqualTo("pending");
        }
    }

    /**
     * Result attribution: an executeId alone must never be enough to write into a run. A second
     * agent that learned the executeId (but not the dispatch token, and not the owning agentId)
     * must not be able to append logs, renew the lease or fin another machine's execution — and
     * even the owning agent must present the exact token minted at dispatch.
     */
    @Test
    void foreignAgentCannotLogOrFinAnotherAgentsExecution() throws Exception {
        try (TestAgent owner = new TestAgent(tcpServer.boundPort());
             TestAgent rogue = new TestAgent(tcpServer.boundPort())) {
            assertThat(owner.hello("agent-owner", List.of()).isOk()).isTrue();
            assertThat(rogue.hello("agent-rogue", List.of()).isOk()).isTrue();

            createTask("agent-owner", "sleep 100", null);
            dispatchService.dispatchOnce();

            Envelope exec = owner.readUntil("exec");
            String executeId = exec.args().get("executeId").asText();
            String token = exec.args().get("token").asText();
            owner.reply(exec.id, Map.of("accepted", true));

            // owner without the token is rejected too: blank tokens must not pass
            Envelope blankToken = owner.request("log", Map.of(
                    "executeId", executeId, "fromSeq", 1, "lines", List.of("no token")));
            assertThat(blankToken.isOk()).isFalse();
            assertThat(blankToken.errorCode()).isEqualTo(ErrorCodes.BAD_TOKEN);

            Envelope ownerLog = owner.request("log", Map.of(
                    "executeId", executeId, "token", token, "fromSeq", 1, "lines", List.of("mine")));
            assertThat(ownerLog.isOk()).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(statusOf(executeId)).isEqualTo(ExecutionStatus.RUNNING));

            // rogue log without a token: rejected by ownership, nothing appended
            Envelope rogueLog = rogue.request("log", Map.of(
                    "executeId", executeId, "fromSeq", 2, "lines", List.of("injected")));
            assertThat(rogueLog.isOk()).isFalse();
            assertThat(rogueLog.errorCode()).isEqualTo(ErrorCodes.FORBIDDEN);

            // even a leaked token must not help a connection with a different agentId
            Envelope rogueLogWithToken = rogue.request("log", Map.of(
                    "executeId", executeId, "token", token, "fromSeq", 2, "lines", List.of("injected")));
            assertThat(rogueLogWithToken.isOk()).isFalse();
            assertThat(rogueLogWithToken.errorCode()).isEqualTo(ErrorCodes.FORBIDDEN);

            // rogue fin must not terminate the run
            Envelope rogueFin = rogue.request("fin", Map.of(
                    "executeId", executeId, "exitCode", 0, "lastLine", "0", "reason", "finished"));
            assertThat(rogueFin.isOk()).isFalse();
            assertThat(rogueFin.errorCode()).isEqualTo(ErrorCodes.FORBIDDEN);
            assertThat(statusOf(executeId)).isEqualTo(ExecutionStatus.RUNNING);

            // rogue heartbeat must not renew the owner's lease; the reply tells it to kill
            // the process it wrongly claims to run
            Envelope rogueHb = rogue.request("hb", Map.of("running", List.of(
                    Map.of("executeId", executeId))));
            assertThat(rogueHb.isOk()).isTrue();
            assertThat(rogueHb.result().get("cancel"))
                    .anyMatch(n -> executeId.equals(n.asText()));

            TaskExecutionEntity run = executionRepository.findByExecuteId(executeId).orElseThrow();
            assertThat(logService.page(run, 0, 100).lines())
                    .as("only the owner's line may be stored")
                    .hasSize(1);

            // the owner with the minted token finishes normally
            Envelope ownerFin = owner.request("fin", Map.of(
                    "executeId", executeId, "token", token, "exitCode", 0,
                    "lastLine", "0", "reason", "finished"));
            assertThat(ownerFin.result().get("applied").asBoolean()).isTrue();
            assertThat(statusOf(executeId)).isEqualTo(ExecutionStatus.PASS);
        }
    }

    @Test
    void secondSessionIsRejectedWhileTheFirstAnswersPing() throws Exception {
        try (TestAgent first = new TestAgent(tcpServer.boundPort())) {
            assertThat(first.hello("agent-dup", List.of()).isOk()).isTrue();
            first.autoAnswerPings(true);

            try (TestAgent second = new TestAgent(tcpServer.boundPort())) {
                Envelope rsp = second.hello("agent-dup", List.of());
                assertThat(rsp.isOk()).isFalse();
                assertThat(rsp.errorCode()).isEqualTo(ErrorCodes.DUP_SESSION);
            }

            AgentEntity agent = agentRepository.findById("agent-dup").orElseThrow();
            assertThat(agent.getStatus()).isEqualTo(AgentStatus.ONLINE);
        }
    }

    @Test
    void reconnectWithoutTheRunReportsTheProcessAsGone() throws Exception {
        String executeId;
        try (TestAgent agent = new TestAgent(tcpServer.boundPort())) {
            assertThat(agent.hello("agent-lost", List.of()).isOk()).isTrue();
            createTask("agent-lost", "sleep 100", null);
            dispatchService.dispatchOnce();

            Envelope exec = agent.readUntil("exec");
            executeId = exec.args().get("executeId").asText();
            agent.reply(exec.id, Map.of("accepted", true));
            agent.request("log", Map.of("executeId", executeId, "token",
                    exec.args().get("token").asText(), "fromSeq", 1, "lines", List.of("working")));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(statusOf(executeId)).isEqualTo(ExecutionStatus.RUNNING));
        }

        // dropped connection keeps the run alive, only the sub status changes
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TaskExecutionEntity exec = executionRepository.findByExecuteId(executeId).orElseThrow();
            assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
            assertThat(exec.getSubStatus()).isEqualTo(TaskExecutionEntity.SUB_DISCONNECTED);
        });

        try (TestAgent again = new TestAgent(tcpServer.boundPort())) {
            assertThat(again.hello("agent-lost", List.of()).isOk()).isTrue();
        }
        assertThat(statusOf(executeId)).isEqualTo(ExecutionStatus.EXCEPTION);
    }

    /**
     * PATCH /api/agents/{id} {concurrency} is accepted while the agent is idle, and the
     * dispatcher starts using the new value immediately. The connected agent only learns its
     * concurrency from the ControlResult of hello/hb frames, so the next heartbeat reply must
     * carry the patched value — otherwise the agent keeps enforcing the old limit and rejects
     * the extra dispatches with "busy" until it reconnects.
     */
    @Test
    void patchedConcurrencyIsDeliveredOnTheNextHeartbeat() throws Exception {
        try (TestAgent agent = new TestAgent(tcpServer.boundPort())) {
            Envelope hello = agent.hello("agent-conc", List.of());
            assertThat(hello.isOk()).isTrue();
            assertThat(hello.result().get("concurrency").asInt()).isEqualTo(1);

            PatchAgentRequest patch = new PatchAgentRequest();
            patch.setConcurrency(3);
            assertThat(agentService.patch("agent-conc", patch).concurrency()).isEqualTo(3);

            Envelope hb = agent.request("hb", Map.of("running", List.of()));
            assertThat(hb.isOk()).isTrue();
            JsonNode concurrency = hb.result().get("concurrency");
            assertThat(concurrency)
                    .as("hb reply must carry the patched concurrency so a connected agent applies it")
                    .isNotNull();
            assertThat(concurrency.asInt()).isEqualTo(3);
        }
    }

    /**
     * `atagent -concurrency N` reaches the server inside the first hello. The server honors it
     * only when the agentId enrolls for the first time; on reconnect the stored value stays
     * authoritative (changes go through PATCH while idle) and the hello reply pushes it back.
     */
    @Test
    void firstHelloRegistersRequestedConcurrency() throws Exception {
        try (TestAgent agent = new TestAgent(tcpServer.boundPort())) {
            Envelope hello = agent.hello("agent-first-conc", List.of(), 2);
            assertThat(hello.isOk()).isTrue();
            assertThat(hello.result().get("concurrency").asInt()).isEqualTo(2);
        }
        assertThat(agentRepository.findById("agent-first-conc").orElseThrow().getConcurrency())
                .isEqualTo(2);
    }

    @Test
    void reconnectHelloDoesNotOverrideStoredConcurrency() throws Exception {
        try (TestAgent agent = new TestAgent(tcpServer.boundPort())) {
            // first enroll without a concurrency wish -> server default (1)
            Envelope hello = agent.hello("agent-keep-conc", List.of());
            assertThat(hello.isOk()).isTrue();
            assertThat(hello.result().get("concurrency").asInt()).isEqualTo(1);
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(agentRepository.findById("agent-keep-conc").orElseThrow().getStatus())
                        .isEqualTo(AgentStatus.OFFLINE));

        try (TestAgent again = new TestAgent(tcpServer.boundPort())) {
            Envelope hello = again.hello("agent-keep-conc", List.of(), 4);
            assertThat(hello.isOk()).isTrue();
            assertThat(hello.result().get("concurrency").asInt())
                    .as("reconnect hello must not override the stored concurrency")
                    .isEqualTo(1);
        }
        assertThat(agentRepository.findById("agent-keep-conc").orElseThrow().getConcurrency())
                .isEqualTo(1);
    }

    private ExecutionStatus statusOf(String executeId) {
        return executionRepository.findByExecuteId(executeId).orElseThrow().getStatus();
    }

    private TaskView createTask(String target, String command, String conditionConfig) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setCommand(command);
        request.setOperator("junit");
        request.setTargets(List.of(Json.mapper().getNodeFactory().textNode(target)));
        if (conditionConfig != null) {
            request.setConditionConfig(Json.read(conditionConfig));
        }
        return taskService.create(request);
    }

    /** Minimal blocking agent: 4 byte big endian length + UTF-8 JSON. */
    private static final class TestAgent implements Closeable {

        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final AtomicLong seq = new AtomicLong();
        private volatile boolean answerPings;

        TestAgent(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        }

        void autoAnswerPings(boolean value) {
            this.answerPings = value;
            if (value) {
                Thread reader = new Thread(() -> {
                    try {
                        while (!socket.isClosed()) {
                            Envelope env = readFrame();
                            if (env.isRequest() && "ping".equals(env.m)) {
                                reply(env.id, Map.of("pong", true));
                            }
                        }
                    } catch (Exception ignored) {
                        // socket closed by the test
                    }
                });
                reader.setDaemon(true);
                reader.start();
            }
        }

        Envelope hello(String agentId, List<Map<String, Object>> running) throws IOException {
            return request("hello", Map.of(
                    "agentId", agentId,
                    "bootId", "boot-1",
                    "ver", "test-1.0",
                    "aliases", List.of(),
                    "running", running));
        }

        Envelope hello(String agentId, List<Map<String, Object>> running, int concurrency)
                throws IOException {
            return request("hello", Map.of(
                    "agentId", agentId,
                    "bootId", "boot-1",
                    "ver", "test-1.0",
                    "aliases", List.of(),
                    "concurrency", concurrency,
                    "running", running));
        }

        Envelope request(String method, Map<String, Object> args) throws IOException {
            long id = seq.incrementAndGet();
            write(Envelope.req(id, method, args));
            while (true) {
                Envelope env = readFrame();
                if (env.isResponse() && env.id != null && env.id == id) {
                    return env;
                }
                if (env.isRequest() && "ping".equals(env.m)) {
                    reply(env.id, Map.of("pong", true));
                }
            }
        }

        Envelope readUntil(String method) throws IOException {
            while (true) {
                Envelope env = readFrame();
                if (env.isRequest() && method.equals(env.m)) {
                    return env;
                }
            }
        }

        void reply(Long id, Map<String, Object> result) throws IOException {
            write(Envelope.ok(id, result));
        }

        private synchronized void write(Envelope env) throws IOException {
            byte[] payload = Json.write(env).getBytes(StandardCharsets.UTF_8);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        }

        private Envelope readFrame() throws IOException {
            int length = in.readInt();
            byte[] payload = new byte[length];
            in.readFully(payload);
            JsonNode node = Json.read(new String(payload, StandardCharsets.UTF_8));
            return Json.convert(node, Envelope.class);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
