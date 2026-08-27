package com.atest.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.atest.common.Json;
import com.atest.config.AtestProperties;
import com.atest.domain.AgentEntity;
import com.atest.domain.AgentStatus;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.AgentEventRepository;
import com.atest.repo.AgentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.sse.AgentSseService;
import com.atest.tcp.AgentConnection;
import com.atest.tcp.AgentRegistry;
import com.atest.tcp.Envelope;
import com.atest.tcp.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Inbound agent frames: hello / hb / log / evt / fin. */
@Slf4j
@Service
public class AgentSessionService {

    /** A run dispatched this recently is not failed by hello reconciliation yet. */
    private static final long RECONCILE_GRACE_MS = 10_000;

    private final AtestProperties props;
    private final AgentRepository agentRepository;
    private final AgentEventRepository agentEventRepository;
    private final TaskExecutionRepository executionRepository;
    private final ExecutionService executionService;
    private final LogService logService;
    private final EventService eventService;
    private final DispatchService dispatchService;
    private final AgentRegistry registry;
    private final AgentSseService agentSse;
    private final ViewMapper viewMapper;

    public AgentSessionService(AtestProperties props,
                               AgentRepository agentRepository,
                               AgentEventRepository agentEventRepository,
                               TaskExecutionRepository executionRepository,
                               ExecutionService executionService,
                               LogService logService,
                               EventService eventService,
                               DispatchService dispatchService,
                               AgentRegistry registry,
                               AgentSseService agentSse,
                               ViewMapper viewMapper) {
        this.props = props;
        this.agentRepository = agentRepository;
        this.agentEventRepository = agentEventRepository;
        this.executionRepository = executionRepository;
        this.executionService = executionService;
        this.logService = logService;
        this.eventService = eventService;
        this.dispatchService = dispatchService;
        this.registry = registry;
        this.agentSse = agentSse;
        this.viewMapper = viewMapper;
    }

    public void onRequest(AgentConnection conn, Envelope env) {
        String method = env.m == null ? "" : env.m;
        if (!"hello".equals(method) && !conn.isRegistered()) {
            conn.replyError(env.id, ErrorCodes.NOT_REGISTERED, "send hello first");
            return;
        }
        switch (method) {
            case "hello" -> onHello(conn, env);
            case "hb" -> onHeartbeat(conn, env);
            case "log" -> onLog(conn, env);
            case "evt" -> onEvent(conn, env);
            case "fin" -> onFin(conn, env);
            case "ping" -> conn.reply(env.id, Map.of("pong", true, "serverTime", System.currentTimeMillis()));
            default -> conn.replyError(env.id, ErrorCodes.UNKNOWN_METHOD, "unsupported method: " + method);
        }
    }

    // ------------------------------------------------------------------ hello

    private void onHello(AgentConnection conn, Envelope env) {
        JsonNode a = env.args();
        String agentId = Json.text(a, "agentId", "aid", "id");
        if (agentId == null || agentId.isBlank()) {
            conn.replyErrorAndClose(env.id, ErrorCodes.BAD_REQUEST, "agentId is required");
            return;
        }
        conn.setAgentId(agentId.trim());
        conn.setBootId(Json.text(a, "bootId", "boot"));
        conn.setVersion(Json.text(a, "ver", "version"));
        conn.setAliases(readStrings(Json.first(a, "aliases", "alias")));
        conn.setSessionId(UUID.randomUUID().toString().replace("-", ""));

        registry.register(conn).whenComplete((outcome, err) -> {
            if (err != null) {
                log.error("hello arbitration failed for {}", conn.getAgentId(), err);
                conn.replyErrorAndClose(env.id, ErrorCodes.INTERNAL, String.valueOf(err.getMessage()));
                return;
            }
            if (outcome == AgentRegistry.Outcome.REJECTED_DUP) {
                conn.serialExecutor().execute(() -> eventService.recordAgent(EventService.T_AGENT_DUP_SESSION,
                        conn.getAgentId(), "已有存活会话，拒绝新连接 " + conn.getRemoteAddr()));
                conn.replyErrorAndClose(env.id, ErrorCodes.DUP_SESSION,
                        "another live session already owns this agentId");
                return;
            }
            conn.serialExecutor().execute(() -> {
                try {
                    Map<String, Object> result = acceptHello(conn, a, outcome);
                    conn.reply(env.id, result);
                } catch (IllegalStateException e) {
                    conn.replyErrorAndClose(env.id, ErrorCodes.TAG_CONFLICT, e.getMessage());
                } catch (Exception e) {
                    log.error("hello failed for {}", conn.getAgentId(), e);
                    conn.replyErrorAndClose(env.id, ErrorCodes.INTERNAL, String.valueOf(e.getMessage()));
                }
            });
        });
    }

    private Map<String, Object> acceptHello(AgentConnection conn, JsonNode a, AgentRegistry.Outcome outcome) {
        String agentId = conn.getAgentId();
        Instant now = Instant.now();
        String requestedTag = Json.text(a, "displayTag", "tag", "name");

        AgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            agent = new AgentEntity();
            agent.setAgentId(agentId);
            agent.setDisplayTag(resolveNewTag(agentId, requestedTag));
            agent.setConcurrency(props.getConcurrency().getDefaultValue());
            agent.setCreatedAt(now);
        } else if (requestedTag != null && !requestedTag.isBlank()
                && !requestedTag.equals(agent.getDisplayTag())) {
            Optional<AgentEntity> owner = agentRepository.findByDisplayTag(requestedTag.trim());
            if (owner.isPresent() && !owner.get().getAgentId().equals(agentId)) {
                throw new IllegalStateException("displayTag 已被占用: " + requestedTag);
            }
            agent.setDisplayTag(requestedTag.trim());
        }
        agent.setStatus(AgentStatus.ONLINE);
        agent.setSessionId(conn.getSessionId());
        agent.setBootId(conn.getBootId());
        agent.setVersion(conn.getVersion());
        agent.setRemoteAddr(conn.getRemoteAddr());
        agent.setAliases(String.join(",", conn.getAliases()));
        agent.setConnectedAt(now);
        agent.setLastHeartbeatAt(now);
        agent.setUpdatedAt(now);
        if (agent.getConcurrency() <= 0) {
            agent.setConcurrency(props.getConcurrency().getDefaultValue());
        }
        agentRepository.save(agent);

        eventService.recordAgent(outcome == AgentRegistry.Outcome.TAKEOVER
                        ? EventService.T_AGENT_TAKEOVER : EventService.T_AGENT_ONLINE,
                agentId, "session=" + conn.getSessionId() + " boot=" + conn.getBootId()
                        + " ver=" + conn.getVersion() + " from=" + conn.getRemoteAddr());

        Map<String, Integer> acks = reconcile(conn, a);
        agentSse.publishAgent(agent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", conn.getSessionId());
        result.put("agentId", agentId);
        result.put("displayTag", agent.getDisplayTag());
        result.put("concurrency", agent.getConcurrency());
        result.put("serverTime", System.currentTimeMillis());
        result.put("logAcks", acks);
        // highest event the server already stored, the agent resumes evt delivery after it
        result.put("lastEvtId", agentEventRepository.maxEvtId(agentId));
        result.put("heartbeatSec", Math.max(5, props.getDispatch().getLeaseSec() / 3));
        return result;
    }

    private String resolveNewTag(String agentId, String requestedTag) {
        String candidate = requestedTag == null || requestedTag.isBlank() ? agentId : requestedTag.trim();
        Optional<AgentEntity> owner = agentRepository.findByDisplayTag(candidate);
        if (owner.isPresent() && !owner.get().getAgentId().equals(agentId)) {
            throw new IllegalStateException("displayTag 已被占用: " + candidate);
        }
        return candidate;
    }

    /**
     * hello carries what the agent is actually running. Anything the server still believes is
     * active but the agent does not know about is a lost process: exception.
     */
    private Map<String, Integer> reconcile(AgentConnection conn, JsonNode a) {
        String agentId = conn.getAgentId();
        Map<String, String> reported = new LinkedHashMap<>();
        JsonNode running = Json.first(a, "running", "runs", "executions");
        if (running != null && running.isArray()) {
            for (JsonNode n : running) {
                if (n.isTextual()) {
                    reported.put(n.asText(), null);
                } else {
                    String executeId = Json.text(n, "executeId", "execId", "eid");
                    if (executeId != null) {
                        reported.put(executeId, Json.text(n, "token", "dispatchToken"));
                    }
                }
            }
        }

        Map<String, Integer> acks = new LinkedHashMap<>();
        Set<String> known = new HashSet<>();
        List<TaskExecutionEntity> active = executionRepository.findByAgentIdAndStatusIn(agentId,
                List.of(ExecutionStatus.DISPATCHING, ExecutionStatus.RUNNING));
        for (TaskExecutionEntity exec : active) {
            known.add(exec.getExecuteId());
            if (reported.containsKey(exec.getExecuteId())) {
                executionService.markRunning(exec, "hello 对账");
                acks.put(exec.getExecuteId(), exec.getLogSeq());
                continue;
            }
            // a frame sent while this hello was in flight may not be reflected in the report yet
            boolean raceWithDispatch = exec.getStatus() == ExecutionStatus.DISPATCHING
                    && exec.getDispatchedAt() != null
                    && exec.getDispatchedAt().isAfter(Instant.now().minusMillis(RECONCILE_GRACE_MS));
            if (raceWithDispatch) {
                continue;
            }
            if (exec.getStatus() == ExecutionStatus.DISPATCHING && !exec.isAcked()) {
                dispatchService.release(exec.getId(), exec.getDispatchToken(), "重连对账，未受理，退回队列");
                continue;
            }
            if (exec.isCancelRequested()) {
                executionService.finish(exec, ExecutionStatus.CANCELED, "重连对账：进程已不存在（取消生效）", null);
            } else {
                executionService.finish(exec, ExecutionStatus.EXCEPTION,
                        "重连对账：agent 上已无该进程", null);
            }
        }

        for (Map.Entry<String, String> entry : reported.entrySet()) {
            if (known.contains(entry.getKey())) {
                continue;
            }
            TaskExecutionEntity exec = executionRepository.findByExecuteId(entry.getKey()).orElse(null);
            if (exec == null || exec.getStatus().isTerminal()) {
                dispatchService.cancelOrphan(agentId, entry.getKey(), entry.getValue());
            }
        }
        return acks;
    }

    // --------------------------------------------------------------------- hb

    private void onHeartbeat(AgentConnection conn, Envelope env) {
        conn.markHeartbeat();
        JsonNode a = env.args();
        String agentId = conn.getAgentId();
        Instant now = Instant.now();

        List<String> reported = new ArrayList<>();
        JsonNode running = Json.first(a, "running", "runs", "executions");
        if (running != null && running.isArray()) {
            for (JsonNode n : running) {
                String executeId = n.isTextual() ? n.asText() : Json.text(n, "executeId", "execId", "eid");
                if (executeId != null) {
                    reported.add(executeId);
                }
            }
        }

        agentRepository.findById(agentId).ifPresent(agent -> {
            agent.setLastHeartbeatAt(now);
            agent.setRunningCount(reported.size());
            agent.setStatus(AgentStatus.ONLINE);
            agent.setUpdatedAt(now);
            agentRepository.save(agent);
        });

        Set<String> pendingCancels = new LinkedHashSet<>();
        for (String executeId : reported) {
            TaskExecutionEntity exec = executionRepository.findByExecuteId(executeId).orElse(null);
            if (exec == null) {
                pendingCancels.add(executeId);
                continue;
            }
            if (exec.getStatus().isTerminal()) {
                pendingCancels.add(executeId);
                continue;
            }
            // heartbeat renews the lease, that is the liveness signal the reaper looks at
            executionService.markRunning(exec, "hb");
            if (exec.isCancelRequested() || exec.isTimeoutRequested()) {
                pendingCancels.add(executeId);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverTime", System.currentTimeMillis());
        result.put("leaseSec", props.getDispatch().getLeaseSec());
        result.put("cancel", pendingCancels);
        conn.reply(env.id, result);
    }

    // -------------------------------------------------------------------- log

    private void onLog(AgentConnection conn, Envelope env) {
        conn.touch();
        JsonNode a = env.args();
        String executeId = Json.text(a, "executeId", "execId", "eid");
        String token = Json.text(a, "token", "dispatchToken");
        JsonNode linesNode = Json.first(a, "lines", "l");
        int fromSeq = intValue(a, 1, "fromSeq", "from", "seq");

        if (executeId == null) {
            conn.replyError(env.id, ErrorCodes.BAD_REQUEST, "executeId is required");
            return;
        }
        TaskExecutionEntity exec = executionRepository.findByExecuteId(executeId).orElse(null);
        if (exec == null) {
            conn.replyError(env.id, ErrorCodes.UNKNOWN_EXECUTION, "unknown executeId " + executeId);
            return;
        }
        if (!ExecutionService.tokenMatches(exec, token)) {
            conn.replyError(env.id, ErrorCodes.BAD_TOKEN, "stale token for " + executeId);
            return;
        }
        if (exec.getStatus().isTerminal()) {
            conn.reply(env.id, Map.of("ackSeq", exec.getLogSeq(), "closed", true));
            return;
        }
        executionService.markRunning(exec, "log");
        List<LogService.IncomingLine> lines = readLogLines(linesNode, fromSeq);
        int ackSeq = logService.appendLines(exec, lines);
        conn.reply(env.id, Map.of("ackSeq", ackSeq));
    }

    // -------------------------------------------------------------------- evt

    private void onEvent(AgentConnection conn, Envelope env) {
        conn.touch();
        JsonNode a = env.args();
        JsonNode events = Json.first(a, "events", "evts", "list");
        List<String> acked = eventService.ingestAgentEvents(conn.getAgentId(), events);
        if (events != null && events.isArray()) {
            for (JsonNode node : events) {
                String type = Json.text(node, "type", "t", "kind");
                String executeId = Json.text(node, "executeId", "execId", "eid");
                if (executeId != null && type != null
                        && (type.contains("start") || type.contains("running"))) {
                    executionService.markRunning(executeId, "evt:" + type);
                }
            }
        }
        conn.reply(env.id, Map.of("acked", acked, "count", acked.size()));
    }

    // -------------------------------------------------------------------- fin

    private void onFin(AgentConnection conn, Envelope env) {
        conn.touch();
        JsonNode a = env.args();
        String executeId = Json.text(a, "executeId", "execId", "eid");
        String token = Json.text(a, "token", "dispatchToken");
        if (executeId == null) {
            conn.replyError(env.id, ErrorCodes.BAD_REQUEST, "executeId is required");
            return;
        }
        TaskExecutionEntity exec = executionRepository.findByExecuteId(executeId).orElse(null);
        if (exec == null) {
            // ACK anyway so the agent stops resending a fin nobody can use
            conn.reply(env.id, Map.of("applied", false, "unknown", true));
            return;
        }
        if (!ExecutionService.tokenMatches(exec, token)) {
            conn.reply(env.id, Map.of("applied", false, "staleToken", true,
                    "status", exec.getStatus().wire()));
            return;
        }
        if (exec.getStatus().isTerminal()) {
            conn.reply(env.id, Map.of("applied", false, "status", exec.getStatus().wire()));
            return;
        }

        List<LogService.IncomingLine> trailing = readLogLines(Json.first(a, "lines", "tail"),
                intValue(a, exec.getLogSeq() + 1, "fromSeq", "from"));
        if (!trailing.isEmpty()) {
            logService.appendLines(exec, trailing);
        }
        Integer exitCode = null;
        JsonNode exitNode = Json.first(a, "exitCode", "code", "rc");
        if (exitNode != null && exitNode.isNumber()) {
            exitCode = exitNode.asInt();
        }
        String lastLine = Json.text(a, "lastLine", "last", "tailLine");
        String reason = Json.text(a, "reason", "why", "cause");

        TaskExecutionEntity finished = executionService.applyFin(exec, exitCode, lastLine, reason);
        conn.reply(env.id, Map.of("applied", true, "status", finished.getStatus().wire()));
    }

    // ------------------------------------------------------------- disconnect

    @Transactional
    public void onDisconnect(AgentConnection conn) {
        String agentId = conn.getAgentId();
        if (agentId == null) {
            return;
        }
        boolean wasCurrent = registry.unregister(conn);
        if (!wasCurrent) {
            return;
        }
        Instant now = Instant.now();
        AgentEntity agent = agentRepository.findById(agentId).orElse(null);
        if (agent != null) {
            agent.setStatus(AgentStatus.OFFLINE);
            agent.setDisconnectedAt(now);
            agent.setSessionId(null);
            agent.setUpdatedAt(now);
            agentRepository.save(agent);
        }
        eventService.recordAgent(EventService.T_AGENT_OFFLINE, agentId,
                "会话断开 session=" + conn.getSessionId());

        for (TaskExecutionEntity exec : executionRepository.findByAgentIdAndStatusIn(agentId,
                List.of(ExecutionStatus.DISPATCHING, ExecutionStatus.RUNNING))) {
            if (exec.getStatus() == ExecutionStatus.DISPATCHING && !exec.isAcked()) {
                dispatchService.release(exec.getId(), exec.getDispatchToken(), "连接断开，未受理，退回队列");
            } else {
                executionService.markDisconnected(exec);
            }
        }
        if (agent != null) {
            agentSse.publishPatch(viewMapper.toAgentView(agent));
        }
    }

    private static List<String> readStrings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode n : node) {
            out.add(n.isTextual() ? n.asText() : n.toString());
        }
        return out;
    }

    /**
     * Go Agent 的 log 帧里每行是结构化对象 {seq,ts,s,x}（x 为正文，seq 为权威序号）。
     * 行内 seq 优先（Agent 的帧级 fromSeq 是 exclusive 语义，按 fromSeq+下标编号会差一，
     * 导致跨批次丢行、ack 停滞）；纯字符串行退回 fromSeq 顺延编号。
     */
    private static List<LogService.IncomingLine> readLogLines(JsonNode node, int fromSeq) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<LogService.IncomingLine> out = new ArrayList<>(node.size());
        int next = Math.max(fromSeq, 1);
        for (JsonNode n : node) {
            String text;
            int seq = next;
            if (n.isObject()) {
                JsonNode t = Json.first(n, "x", "text", "line", "msg");
                text = t != null && t.isTextual() ? t.asText() : n.toString();
                JsonNode s = n.get("seq");
                if (s != null && s.isNumber() && s.asInt() > 0) {
                    seq = s.asInt();
                }
            } else {
                text = n.isTextual() ? n.asText() : n.toString();
            }
            out.add(new LogService.IncomingLine(seq, text));
            next = seq + 1;
        }
        return out;
    }

    private static int intValue(JsonNode node, int fallback, String... names) {
        JsonNode v = Json.first(node, names);
        return v == null || !v.isNumber() ? fallback : v.asInt();
    }
}
