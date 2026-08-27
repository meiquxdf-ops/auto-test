package com.atest.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.atest.common.ApiException;
import com.atest.config.AtestProperties;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.domain.TaskStatus;
import com.atest.judge.JudgeResult;
import com.atest.judge.JudgeService;
import com.atest.repo.AgentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.sse.AgentSseService;
import com.atest.sse.ExecutionSseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns every execution state transition; {@code fin} is the only source of a normal terminal state. */
@Slf4j
@Service
public class ExecutionService {

    private final AtestProperties props;
    private final TaskExecutionRepository executionRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final JudgeService judgeService;
    private final EventService eventService;
    private final ExecutionSseService executionSse;
    private final AgentSseService agentSse;

    public ExecutionService(AtestProperties props,
                            TaskExecutionRepository executionRepository,
                            TaskRepository taskRepository,
                            AgentRepository agentRepository,
                            JudgeService judgeService,
                            EventService eventService,
                            ExecutionSseService executionSse,
                            AgentSseService agentSse) {
        this.props = props;
        this.executionRepository = executionRepository;
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
        this.judgeService = judgeService;
        this.eventService = eventService;
        this.executionSse = executionSse;
        this.agentSse = agentSse;
    }

    @Transactional(readOnly = true)
    public Optional<TaskExecutionEntity> findByExecuteId(String executeId) {
        return executionRepository.findByExecuteId(executeId);
    }

    /** REST accepts either the numeric row id or the wire executeId. */
    @Transactional(readOnly = true)
    public TaskExecutionEntity require(String idOrExecuteId) {
        if (idOrExecuteId == null || idOrExecuteId.isBlank()) {
            throw ApiException.badRequest("execution id 不能为空");
        }
        Optional<TaskExecutionEntity> found = Optional.empty();
        if (idOrExecuteId.chars().allMatch(Character::isDigit)) {
            found = executionRepository.findById(Long.parseLong(idOrExecuteId));
        }
        if (found.isEmpty()) {
            found = executionRepository.findByExecuteId(idOrExecuteId);
        }
        return found.orElseThrow(() -> ApiException.notFound("execution 不存在: " + idOrExecuteId));
    }

    public Instant newLeaseDeadline() {
        return Instant.now().plusSeconds(props.getDispatch().getLeaseSec());
    }

    @Transactional
    public void markAcked(String executeId, String token) {
        TaskExecutionEntity exec = executionRepository.findByExecuteId(executeId).orElse(null);
        if (exec == null || !tokenMatches(exec, token)) {
            return;
        }
        if (exec.getStatus() == ExecutionStatus.DISPATCHING) {
            exec.setAcked(true);
            exec.setLeaseExpireAt(newLeaseDeadline());
            exec.setUpdatedAt(Instant.now());
            executionRepository.save(exec);
            eventService.record(EventService.T_ACK, exec.getAgentId(), executeId, exec.getTaskId(), "agent 已受理");
            publishExecution(exec);
        }
    }

    /** dispatching -> running, triggered by the first log line, a started event or a heartbeat. */
    @Transactional
    public void markRunning(String executeId, String source) {
        TaskExecutionEntity exec = executionRepository.findByExecuteId(executeId).orElse(null);
        if (exec == null) {
            return;
        }
        markRunning(exec, source);
    }

    @Transactional
    public void markRunning(TaskExecutionEntity exec, String source) {
        if (exec.getStatus().isTerminal()) {
            return;
        }
        boolean changed = false;
        if (exec.getStatus() != ExecutionStatus.RUNNING) {
            exec.setStatus(ExecutionStatus.RUNNING);
            exec.setStartedAt(exec.getStartedAt() == null ? Instant.now() : exec.getStartedAt());
            changed = true;
            eventService.record(EventService.T_RUNNING, exec.getAgentId(), exec.getExecuteId(), exec.getTaskId(),
                    "开始执行 (" + source + ")");
        }
        if (exec.getSubStatus() != null) {
            exec.setSubStatus(null);
            exec.setDisconnectedAt(null);
            changed = true;
        }
        exec.setAcked(true);
        exec.setLeaseExpireAt(newLeaseDeadline());
        exec.setUpdatedAt(Instant.now());
        executionRepository.save(exec);
        if (changed) {
            touchTask(exec.getTaskId());
            publishExecution(exec);
            publishAgent(exec.getAgentId());
        }
    }

    /** Lost connection: running keeps running, only the sub status changes. */
    @Transactional
    public void markDisconnected(TaskExecutionEntity exec) {
        if (!exec.getStatus().isActive() || TaskExecutionEntity.SUB_DISCONNECTED.equals(exec.getSubStatus())) {
            return;
        }
        exec.setSubStatus(TaskExecutionEntity.SUB_DISCONNECTED);
        exec.setDisconnectedAt(Instant.now());
        exec.setUpdatedAt(Instant.now());
        executionRepository.save(exec);
        eventService.record(EventService.T_DISCONNECTED, exec.getAgentId(), exec.getExecuteId(), exec.getTaskId(),
                "agent 失联，执行保持 running/disconnected");
        publishExecution(exec);
    }

    /** The fin frame: the only path to a judged terminal state. */
    @Transactional
    public TaskExecutionEntity applyFin(TaskExecutionEntity exec, Integer exitCode, String finLastLine, String reason) {
        if (exec.getStatus().isTerminal()) {
            return exec;
        }
        String lastLine = finLastLine != null && !finLastLine.isBlank()
                ? JudgeService.normalizeLine(finLastLine)
                : JudgeService.normalizeLine(exec.getLastLine());
        String normalizedReason = reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT);

        ExecutionStatus status;
        String detail;
        String matchedRule = null;
        if (exec.isCancelRequested() || normalizedReason.startsWith("cancel")) {
            status = ExecutionStatus.CANCELED;
            detail = "用户取消";
        } else if (exec.isTimeoutRequested() || normalizedReason.contains("timeout")) {
            status = ExecutionStatus.EXCEPTION;
            detail = "执行超时被杀";
        } else if (isFailureReason(normalizedReason)) {
            status = ExecutionStatus.EXCEPTION;
            detail = "agent 上报异常: " + reason;
        } else {
            TaskEntity task = taskRepository.findById(exec.getTaskId()).orElse(null);
            JudgeResult result = judgeService.judge(lastLine, exitCode,
                    task == null ? null : task.getConditionConfig());
            status = result.status();
            detail = result.reason();
            matchedRule = result.matchedRule();
        }
        exec.setExitCode(exitCode);
        exec.setLastLine(lastLine);
        return finish(exec, status, detail, matchedRule);
    }

    private boolean isFailureReason(String reason) {
        return reason.contains("error") || reason.contains("kill") || reason.contains("spawn")
                || reason.contains("missing") || reason.contains("crash") || reason.contains("gone");
    }

    @Transactional
    public TaskExecutionEntity finish(TaskExecutionEntity exec, ExecutionStatus status, String reason,
                                      String matchedRule) {
        if (exec.getStatus().isTerminal()) {
            return exec;
        }
        Instant now = Instant.now();
        exec.setStatus(status);
        exec.setSubStatus(null);
        exec.setReason(reason);
        exec.setMatchedRule(matchedRule);
        exec.setLeaseExpireAt(null);
        exec.setFinishedAt(now);
        exec.setUpdatedAt(now);
        if (exec.getStartedAt() == null && status != ExecutionStatus.CANCELED) {
            exec.setStartedAt(now);
        }
        executionRepository.save(exec);
        eventService.record(status == ExecutionStatus.CANCELED ? EventService.T_CANCELED : EventService.T_FINISHED,
                exec.getAgentId(), exec.getExecuteId(), exec.getTaskId(),
                status.wire() + (reason == null ? "" : " - " + reason));
        touchTask(exec.getTaskId());
        publishExecution(exec);
        executionSse.publishEnd(exec.getExecuteId(), statusPayload(exec));
        publishAgent(exec.getAgentId());
        return exec;
    }

    /** Marks a cancel request; the terminal state still arrives with fin unless the agent is gone. */
    @Transactional
    public void markCancelRequested(TaskExecutionEntity exec) {
        exec.setCancelRequested(true);
        exec.setUpdatedAt(Instant.now());
        executionRepository.save(exec);
        publishExecution(exec);
    }

    @Transactional
    public void markTimeoutRequested(TaskExecutionEntity exec) {
        exec.setTimeoutRequested(true);
        exec.setUpdatedAt(Instant.now());
        executionRepository.save(exec);
        eventService.record(EventService.T_TIMEOUT, exec.getAgentId(), exec.getExecuteId(), exec.getTaskId(),
                "超过 timeoutSec，请求 agent 杀进程组");
        publishExecution(exec);
    }

    /** Recomputes the parent task status from its executions. */
    @Transactional
    public void touchTask(Long taskId) {
        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        List<TaskExecutionEntity> executions = executionRepository.findByTaskIdOrderByIdAsc(taskId);
        TaskStatus next = deriveTaskStatus(executions);
        if (next != task.getStatus()) {
            task.setStatus(next);
            task.setUpdatedAt(Instant.now());
            taskRepository.save(task);
        }
    }

    public static TaskStatus deriveTaskStatus(List<TaskExecutionEntity> executions) {
        if (executions.isEmpty()) {
            return TaskStatus.PENDING;
        }
        boolean anyActive = false;
        boolean anyPending = false;
        boolean allCanceled = true;
        for (TaskExecutionEntity e : executions) {
            if (e.getStatus().isActive()) {
                anyActive = true;
            }
            if (e.getStatus() == ExecutionStatus.PENDING) {
                anyPending = true;
            }
            if (e.getStatus() != ExecutionStatus.CANCELED) {
                allCanceled = false;
            }
        }
        if (anyActive) {
            return TaskStatus.RUNNING;
        }
        if (anyPending) {
            return TaskStatus.PENDING;
        }
        return allCanceled ? TaskStatus.CANCELED : TaskStatus.FINISHED;
    }

    public Map<String, Object> statusPayload(TaskExecutionEntity exec) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executeId", exec.getExecuteId());
        payload.put("status", exec.getStatus().wire());
        payload.put("subStatus", exec.getSubStatus());
        payload.put("exitCode", exec.getExitCode());
        payload.put("reason", exec.getReason());
        payload.put("truncated", exec.isTruncated());
        payload.put("logSeq", exec.getLogSeq());
        payload.put("finished", exec.getStatus().isTerminal());
        payload.put("ts", System.currentTimeMillis());
        return payload;
    }

    public void publishExecution(TaskExecutionEntity exec) {
        executionSse.publishStatus(exec.getExecuteId(), statusPayload(exec));
    }

    public void publishAgent(String agentId) {
        agentRepository.findById(agentId).ifPresent(agentSse::publishAgent);
    }

    public static boolean tokenMatches(TaskExecutionEntity exec, String token) {
        return token == null || token.isBlank() || token.equals(exec.getDispatchToken());
    }
}
