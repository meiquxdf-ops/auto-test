package com.atest.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.atest.common.Json;
import com.atest.config.AtestProperties;
import com.atest.domain.AgentEntity;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.AgentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.tcp.AgentConnection;
import com.atest.tcp.AgentRegistry;
import com.atest.tcp.Envelope;
import com.atest.tcp.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queue drain. A slot is taken with a DB compare-and-set on the pending row, so a row can only ever
 * be dispatched once, and the agent is always addressed by its registered agentId connection.
 */
@Slf4j
@Service
public class DispatchService {

    private final AtestProperties props;
    private final TaskExecutionRepository executionRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final AgentRegistry registry;
    private final ExecutionService executionService;
    private final EventService eventService;
    private final ExecutorService workExecutor;
    private final AtomicBoolean ticking = new AtomicBoolean();

    public DispatchService(AtestProperties props,
                           TaskExecutionRepository executionRepository,
                           TaskRepository taskRepository,
                           AgentRepository agentRepository,
                           AgentRegistry registry,
                           ExecutionService executionService,
                           EventService eventService,
                           @Qualifier("agentWorkExecutor") ExecutorService workExecutor) {
        this.props = props;
        this.executionRepository = executionRepository;
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
        this.registry = registry;
        this.executionService = executionService;
        this.eventService = eventService;
        this.workExecutor = workExecutor;
    }

    @Scheduled(fixedDelayString = "${atest.dispatch.interval-ms:1000}")
    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        try {
            dispatchOnce();
        } catch (Exception e) {
            log.error("dispatch tick failed", e);
        } finally {
            ticking.set(false);
        }
    }

    public int dispatchOnce() {
        int dispatched = 0;
        for (String agentId : executionRepository.findAgentIdsWithPending()) {
            AgentConnection conn = registry.get(agentId).orElse(null);
            if (conn == null) {
                continue;
            }
            AgentEntity agent = agentRepository.findById(agentId).orElse(null);
            if (agent == null) {
                continue;
            }
            int capacity = Math.max(1, agent.getConcurrency());
            int active = (int) executionRepository.countByAgentIdAndStatusIn(agentId,
                    List.of(ExecutionStatus.DISPATCHING, ExecutionStatus.RUNNING));
            int free = capacity - active;
            if (free <= 0) {
                continue;
            }
            List<TaskExecutionEntity> candidates =
                    executionRepository.findPendingForAgent(agentId, PageRequest.of(0, free));
            for (TaskExecutionEntity exec : candidates) {
                if (claimAndSend(exec, conn)) {
                    dispatched++;
                }
            }
        }
        return dispatched;
    }

    private boolean claimAndSend(TaskExecutionEntity exec, AgentConnection conn) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant lease = now.plusSeconds(props.getDispatch().getLeaseSec());
        if (executionRepository.casClaim(exec.getId(), token, lease, now) != 1) {
            return false;
        }
        TaskEntity task = taskRepository.findById(exec.getTaskId()).orElse(null);
        if (task == null) {
            release(exec.getId(), token, "task 不存在");
            return false;
        }
        Map<String, Object> args = execArgs(exec, task, token);
        eventService.record(EventService.T_DISPATCHING, exec.getAgentId(), exec.getExecuteId(), exec.getTaskId(),
                "下发到 " + exec.getAgentId() + " token=" + token);
        conn.request("exec", args, props.getAgent().getRequestTimeoutMs())
                .whenCompleteAsync((rsp, err) -> handleExecResponse(exec, token, rsp, err), workExecutor);
        return true;
    }

    private void handleExecResponse(TaskExecutionEntity exec, String token,
                                    Envelope rsp, Throwable err) {
        try {
            if (err != null) {
                release(exec.getId(), token, "exec 未收到 ACK: " + err.getMessage());
                return;
            }
            if (rsp.isOk()) {
                // ACK only means the agent received the frame, the run is not finished by it
                executionService.markAcked(exec.getExecuteId(), token);
                return;
            }
            String code = rsp.errorCode();
            if (ErrorCodes.DUP_TOKEN.equals(code)) {
                executionService.markAcked(exec.getExecuteId(), token);
                return;
            }
            eventService.record(EventService.T_REJECTED, exec.getAgentId(), exec.getExecuteId(), exec.getTaskId(),
                    "agent 拒绝: " + code + " " + rsp.errorMessage());
            release(exec.getId(), token, "agent 拒绝: " + code);
        } catch (Exception e) {
            log.error("failed to handle exec response for {}", exec.getExecuteId(), e);
        }
    }

    /** Returns the slot to the queue; only succeeds while the row still carries our token. */
    @Transactional
    public void release(Long executionId, String token, String why) {
        int updated = executionRepository.casRelease(executionId, token, Instant.now());
        if (updated == 1) {
            executionRepository.findById(executionId).ifPresent(exec -> {
                eventService.record(EventService.T_REJECTED, exec.getAgentId(), exec.getExecuteId(),
                        exec.getTaskId(), "回到队列: " + why);
                executionService.publishExecution(exec);
            });
        }
    }

    private Map<String, Object> execArgs(TaskExecutionEntity exec, TaskEntity task, String token) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("executeId", exec.getExecuteId());
        args.put("token", token);
        // Go Agent 侧 taskId 是字符串（proto.ExecArgs），发数字会被 bad_request 拒收
        args.put("taskId", String.valueOf(task.getId()));
        args.put("name", task.getName());
        args.put("command", task.getCommand());
        args.put("cwd", task.getCwd());
        args.put("env", envMap(task));
        args.put("timeoutSec", task.getTimeoutSec() > 0
                ? task.getTimeoutSec() : props.getDispatch().getDefaultTimeoutSec());
        args.put("operator", task.getOperator());
        args.put("fromSeq", exec.getLogSeq() + 1);
        return args;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> envMap(TaskEntity task) {
        JsonNode node = Json.read(task.getEnv());
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> env = Json.convert(node, Map.class);
        return env == null ? Map.of() : env;
    }

    /** User cancel or watchdog timeout; the agent kills the whole process group by token. */
    @Transactional
    public boolean requestCancel(TaskExecutionEntity exec, String reason, boolean userInitiated) {
        if (exec.getStatus().isTerminal()) {
            return false;
        }
        if (exec.getStatus() == ExecutionStatus.PENDING) {
            executionService.finish(exec, ExecutionStatus.CANCELED, reason, null);
            return true;
        }
        if (exec.getStatus() == ExecutionStatus.DISPATCHING && !exec.isAcked()) {
            executionService.finish(exec, ExecutionStatus.CANCELED, reason + " (未被受理)", null);
            return true;
        }
        if (userInitiated) {
            executionService.markCancelRequested(exec);
        } else {
            executionService.markTimeoutRequested(exec);
        }
        AgentConnection conn = registry.get(exec.getAgentId()).orElse(null);
        if (conn == null) {
            eventService.record(EventService.T_CANCEL_SENT, exec.getAgentId(), exec.getExecuteId(), exec.getTaskId(),
                    "agent 离线，取消请求挂起，等待重连对账");
            return false;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("executeId", exec.getExecuteId());
        args.put("token", exec.getDispatchToken());
        args.put("reason", reason);
        eventService.record(EventService.T_CANCEL_SENT, exec.getAgentId(), exec.getExecuteId(), exec.getTaskId(),
                "下发 cancel: " + reason);
        conn.request("cancel", args, props.getAgent().getRequestTimeoutMs())
                .whenCompleteAsync((rsp, err) -> {
                    if (err != null) {
                        log.warn("cancel for {} not acked: {}", exec.getExecuteId(), err.toString());
                    }
                }, workExecutor);
        return true;
    }

    /** Stops everything currently running on one machine. */
    public boolean sendStop(String agentId, String reason) {
        AgentConnection conn = registry.get(agentId).orElse(null);
        if (conn == null) {
            return false;
        }
        for (TaskExecutionEntity exec : executionRepository.findByAgentIdAndStatusIn(agentId,
                List.of(ExecutionStatus.DISPATCHING, ExecutionStatus.RUNNING))) {
            executionService.markCancelRequested(exec);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("reason", reason);
        eventService.recordAgent(EventService.T_AGENT_STOP, agentId, "下发 stop: " + reason);
        conn.request("stop", args, props.getAgent().getRequestTimeoutMs())
                .whenCompleteAsync((rsp, err) -> {
                    if (err != null) {
                        log.warn("stop for {} not acked: {}", agentId, err.toString());
                    }
                }, workExecutor);
        return true;
    }

    /**
     * Restart is expressed with the frozen frame set: stop everything, then drop the session so the
     * agent supervisor brings the process back and a fresh hello reconciles state.
     */
    public boolean sendRestart(String agentId) {
        AgentConnection conn = registry.get(agentId).orElse(null);
        if (conn == null) {
            return false;
        }
        eventService.recordAgent(EventService.T_AGENT_RESTART, agentId, "下发 stop(reason=restart) 并断开会话");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("reason", "restart");
        conn.request("stop", args, props.getAgent().getRequestTimeoutMs())
                .whenCompleteAsync((rsp, err) -> conn.close(), workExecutor);
        return true;
    }

    /** Cancels a run the agent reports but the server does not know about. */
    public void cancelOrphan(String agentId, String executeId, String token) {
        AgentConnection conn = registry.get(agentId).orElse(null);
        if (conn == null) {
            return;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("executeId", executeId);
        args.put("token", token);
        args.put("reason", "orphan");
        eventService.record(EventService.T_RECONCILE, agentId, executeId, null, "server 无此执行，下发 cancel");
        conn.request("cancel", args, props.getAgent().getRequestTimeoutMs());
    }
}
