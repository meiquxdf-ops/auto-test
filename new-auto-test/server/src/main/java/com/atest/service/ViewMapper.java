package com.atest.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.atest.common.Json;
import com.atest.config.AtestProperties;
import com.atest.domain.AgentEntity;
import com.atest.domain.AgentEventEntity;
import com.atest.domain.CallbackStatus;
import com.atest.domain.DispatchEventEntity;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.TaskExecutionRepository;
import com.atest.tcp.AgentRegistry;
import com.atest.web.dto.AgentView;
import com.atest.web.dto.ExecutionView;
import com.atest.web.dto.TaskView;
import com.atest.web.dto.TimelineItemView;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

@Service
public class ViewMapper {

    private final AtestProperties props;
    private final AgentRegistry registry;
    private final TaskExecutionRepository executionRepository;

    public ViewMapper(AtestProperties props, AgentRegistry registry, TaskExecutionRepository executionRepository) {
        this.props = props;
        this.registry = registry;
        this.executionRepository = executionRepository;
    }

    public Map<String, Integer> activeCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (Object[] row : executionRepository.countActivePerAgent()) {
            counts.put((String) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    public AgentView toAgentView(AgentEntity agent) {
        return toAgentView(agent, activeCountFor(agent.getAgentId()));
    }

    public int activeCountFor(String agentId) {
        return (int) executionRepository.countByAgentIdAndStatusIn(agentId,
                List.of(ExecutionStatus.DISPATCHING, ExecutionStatus.RUNNING));
    }

    public AgentView toAgentView(AgentEntity agent, int activeCount) {
        boolean online = registry.isOnline(agent.getAgentId());
        List<String> aliases = agent.getAliases() == null || agent.getAliases().isBlank()
                ? List.of()
                : List.of(agent.getAliases().split(","));
        return new AgentView(
                agent.getAgentId(),
                agent.getDisplayTag(),
                online ? "online" : "offline",
                online,
                agent.getVersion(),
                agent.getBootId(),
                agent.getSessionId(),
                agent.getRemoteAddr(),
                aliases,
                agent.getConcurrency(),
                props.getConcurrency().getMaxValue(),
                agent.getRunningCount(),
                activeCount,
                activeCount == 0,
                agent.getConnectedAt(),
                agent.getDisconnectedAt(),
                agent.getLastHeartbeatAt());
    }

    public ExecutionView toExecutionView(TaskExecutionEntity e, TaskEntity task) {
        return new ExecutionView(
                e.getId(),
                e.getExecuteId(),
                e.getTaskId(),
                task == null ? null : task.getName(),
                e.getAgentId(),
                e.getAgentTag(),
                e.getTargetRaw(),
                e.getStatus().wire(),
                e.getSubStatus(),
                TaskExecutionEntity.SUB_DISCONNECTED.equals(e.getSubStatus()),
                e.getExitCode(),
                e.getLastLine(),
                e.getReason(),
                e.getMatchedRule(),
                e.getLogSeq(),
                e.getLogMinSeq(),
                e.getLogBytes(),
                e.isTruncated(),
                e.getAttempt(),
                e.isCancelRequested(),
                e.isTimeoutRequested(),
                task == null ? null : task.getCommand(),
                task == null ? null : task.getCwd(),
                task == null ? null : task.getTimeoutSec(),
                e.getDispatchedAt(),
                e.getStartedAt(),
                e.getFinishedAt(),
                e.getLeaseExpireAt(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    public TaskView toTaskView(TaskEntity task, List<TaskExecutionEntity> executions, boolean includeExecutions) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TaskExecutionEntity e : executions) {
            counts.merge(e.getStatus().wire(), 1L, Long::sum);
        }
        List<ExecutionView> views = new ArrayList<>();
        if (includeExecutions) {
            for (TaskExecutionEntity e : executions) {
                views.add(toExecutionView(e, task));
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, String> env = task.getEnv() == null
                ? Map.of()
                : (Map<String, String>) Json.convert(Json.read(task.getEnv()), Map.class);
        JsonNode condition = Json.read(task.getConditionConfig());
        List<String> targets = new ArrayList<>();
        JsonNode targetsNode = Json.read(task.getTargets());
        if (targetsNode != null && targetsNode.isArray()) {
            targetsNode.forEach(n -> targets.add(n.isTextual() ? n.asText() : n.toString()));
        }
        return new TaskView(
                task.getId(),
                task.getName(),
                task.getCommand(),
                task.getCwd(),
                env == null ? Map.of() : env,
                condition,
                targets,
                task.getOperator(),
                task.getTimeoutSec(),
                task.getPriority(),
                task.getQueueOrder(),
                task.getStatus().wire(),
                task.getTotalCount(),
                task.getRerunOf(),
                task.getRequestId(),
                task.getCallbackUrl(),
                task.getCallbackStatus() == null ? CallbackStatus.NONE.wire() : task.getCallbackStatus().wire(),
                task.getCallbackAttempts(),
                task.getCallbackLastError(),
                task.getCallbackLastAt(),
                counts,
                includeExecutions ? views : null,
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    public TimelineItemView toTimelineItem(AgentEventEntity e) {
        return new TimelineItemView("agent", e.getId(), e.getType(), e.getAgentId(), e.getExecuteId(), null,
                e.getMessage(), e.getEventTime() != null ? e.getEventTime() : e.getCreatedAt());
    }

    public TimelineItemView toTimelineItem(DispatchEventEntity e) {
        return new TimelineItemView("server", e.getId(), e.getType(), e.getAgentId(), e.getExecuteId(), e.getTaskId(),
                e.getDetail(), e.getCreatedAt());
    }
}
