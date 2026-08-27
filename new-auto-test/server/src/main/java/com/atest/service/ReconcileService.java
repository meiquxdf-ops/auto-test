package com.atest.service;

import java.time.Instant;
import java.util.List;

import com.atest.config.AtestProperties;
import com.atest.domain.AgentEntity;
import com.atest.domain.AgentStatus;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.AgentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.tcp.AgentRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lease reaper and watchdog.
 *
 * <ul>
 *   <li>lease expired while the agent is gone: stays running with sub status disconnected</li>
 *   <li>lease expired while the agent is connected: the process is no longer there -&gt; exception</li>
 *   <li>disconnected for too long: exception</li>
 *   <li>over timeoutSec: cancel is sent, the run ends as exception</li>
 * </ul>
 */
@Slf4j
@Service
public class ReconcileService {

    private final AtestProperties props;
    private final TaskExecutionRepository executionRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final AgentRegistry registry;
    private final ExecutionService executionService;
    private final DispatchService dispatchService;
    private final EventService eventService;

    public ReconcileService(AtestProperties props,
                            TaskExecutionRepository executionRepository,
                            TaskRepository taskRepository,
                            AgentRepository agentRepository,
                            AgentRegistry registry,
                            ExecutionService executionService,
                            DispatchService dispatchService,
                            EventService eventService) {
        this.props = props;
        this.executionRepository = executionRepository;
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
        this.registry = registry;
        this.executionService = executionService;
        this.dispatchService = dispatchService;
        this.eventService = eventService;
    }

    /** Nothing can be connected right after a boot, so old sessions must not look alive. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        Instant now = Instant.now();
        for (AgentEntity agent : agentRepository.findByStatus(AgentStatus.ONLINE)) {
            agent.setStatus(AgentStatus.OFFLINE);
            agent.setSessionId(null);
            agent.setDisconnectedAt(now);
            agent.setUpdatedAt(now);
            agentRepository.save(agent);
        }
        for (TaskExecutionEntity exec : executionRepository.findAllActive()) {
            if (exec.getStatus() == ExecutionStatus.DISPATCHING && !exec.isAcked()) {
                dispatchService.release(exec.getId(), exec.getDispatchToken(), "server 重启，未受理，退回队列");
            } else {
                executionService.markDisconnected(exec);
            }
        }
    }

    @Scheduled(fixedDelayString = "${atest.dispatch.reconcile-interval-ms:5000}")
    public void reconcile() {
        try {
            reapLeases();
            enforceTimeouts();
        } catch (Exception e) {
            log.error("reconcile failed", e);
        }
    }

    @Transactional
    public void reapLeases() {
        Instant now = Instant.now();
        List<TaskExecutionEntity> expired = executionRepository.findExpiredLeases(now);
        for (TaskExecutionEntity exec : expired) {
            boolean online = registry.isOnline(exec.getAgentId());
            if (!online) {
                executionService.markDisconnected(exec);
                int deadAfter = props.getDispatch().getDisconnectedTimeoutSec();
                if (deadAfter > 0 && exec.getDisconnectedAt() != null
                        && exec.getDisconnectedAt().isBefore(now.minusSeconds(deadAfter))) {
                    executionService.finish(exec,
                            exec.isCancelRequested() ? ExecutionStatus.CANCELED : ExecutionStatus.EXCEPTION,
                            "失联超过 " + deadAfter + "s，判定进程已不存在", null);
                }
                continue;
            }
            // connected but no heartbeat mentioned this run: the process is gone
            eventService.record(EventService.T_LEASE_EXPIRED, exec.getAgentId(), exec.getExecuteId(),
                    exec.getTaskId(), "租约过期且 agent 在线，对账认定进程不存在");
            if (exec.getStatus() == ExecutionStatus.DISPATCHING && !exec.isAcked()) {
                dispatchService.release(exec.getId(), exec.getDispatchToken(), "租约过期，未受理，退回队列");
            } else if (exec.isCancelRequested()) {
                executionService.finish(exec, ExecutionStatus.CANCELED, "取消后进程已消失", null);
            } else {
                executionService.finish(exec, ExecutionStatus.EXCEPTION, "租约过期，对账确认进程不在", null);
            }
        }
    }

    @Transactional
    public void enforceTimeouts() {
        Instant now = Instant.now();
        int grace = props.getDispatch().getTimeoutGraceSec();
        for (TaskExecutionEntity exec : executionRepository.findRunningForTimeoutCheck()) {
            TaskEntity task = taskRepository.findById(exec.getTaskId()).orElse(null);
            if (task == null) {
                continue;
            }
            int timeoutSec = task.getTimeoutSec() > 0
                    ? task.getTimeoutSec() : props.getDispatch().getDefaultTimeoutSec();
            if (timeoutSec <= 0 || exec.getStartedAt() == null) {
                continue;
            }
            if (exec.getStartedAt().plusSeconds((long) timeoutSec + grace).isAfter(now)) {
                continue;
            }
            dispatchService.requestCancel(exec, "timeout after " + timeoutSec + "s", false);
        }
    }
}
