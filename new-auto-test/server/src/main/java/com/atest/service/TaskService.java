package com.atest.service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.atest.common.ApiException;
import com.atest.common.Json;
import com.atest.config.AtestProperties;
import com.atest.domain.AgentEntity;
import com.atest.domain.CallbackStatus;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.domain.TaskStatus;
import com.atest.judge.JudgeService;
import com.atest.repo.AgentRepository;
import com.atest.repo.ExecutionLogRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.web.dto.BatchCreateTaskRequest;
import com.atest.web.dto.CreateTaskRequest;
import com.atest.web.dto.RerunRequest;
import com.atest.web.dto.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class TaskService {

    private final AtestProperties props;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final ExecutionLogRepository logRepository;
    private final AgentRepository agentRepository;
    private final JudgeService judgeService;
    private final ExecutionService executionService;
    private final DispatchService dispatchService;
    private final EventService eventService;
    private final ViewMapper viewMapper;

    public TaskService(AtestProperties props,
                       TaskRepository taskRepository,
                       TaskExecutionRepository executionRepository,
                       ExecutionLogRepository logRepository,
                       AgentRepository agentRepository,
                       JudgeService judgeService,
                       ExecutionService executionService,
                       DispatchService dispatchService,
                       EventService eventService,
                       ViewMapper viewMapper) {
        this.props = props;
        this.taskRepository = taskRepository;
        this.executionRepository = executionRepository;
        this.logRepository = logRepository;
        this.agentRepository = agentRepository;
        this.judgeService = judgeService;
        this.executionService = executionService;
        this.dispatchService = dispatchService;
        this.eventService = eventService;
        this.viewMapper = viewMapper;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public TaskView create(CreateTaskRequest request) {
        if (request.getCommand() == null || request.getCommand().isBlank()) {
            throw ApiException.badRequest("command 不能为空");
        }
        String conditionJson = request.getConditionConfig() == null || request.getConditionConfig().isNull()
                ? null : Json.write(request.getConditionConfig());
        try {
            judgeService.validate(conditionJson);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }

        List<ResolvedTarget> targets = resolveTargets(request.getTargets());
        Instant now = Instant.now();

        TaskEntity task = new TaskEntity();
        task.setName(request.getName() == null || request.getName().isBlank()
                ? defaultName(request.getCommand()) : request.getName().trim());
        task.setCommand(request.getCommand());
        task.setCwd(request.getCwd());
        task.setEnv(request.getEnv() == null || request.getEnv().isEmpty() ? null : Json.write(request.getEnv()));
        task.setConditionConfig(conditionJson);
        task.setTargets(Json.write(targets.stream().map(ResolvedTarget::raw).toList()));
        task.setOperator(request.getOperator());
        task.setTimeoutSec(request.getTimeoutSec() == null || request.getTimeoutSec() <= 0
                ? props.getDispatch().getDefaultTimeoutSec() : request.getTimeoutSec());
        task.setPriority(request.getPriority() == null ? 0 : request.getPriority());
        task.setQueueOrder(taskRepository.maxQueueOrder() + 1);
        task.setStatus(TaskStatus.PENDING);
        task.setTotalCount(targets.size());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskRepository.save(task);

        List<TaskExecutionEntity> executions = new ArrayList<>();
        for (ResolvedTarget target : targets) {
            executions.add(newExecution(task, target, now));
        }
        executionRepository.saveAll(executions);

        eventService.record(EventService.T_TASK_CREATED, null, null, task.getId(),
                "创建任务 " + task.getName() + "，目标 " + targets.size() + " 台，operator=" + task.getOperator());
        for (TaskExecutionEntity exec : executions) {
            eventService.record(EventService.T_CREATED, exec.getAgentId(), exec.getExecuteId(), task.getId(),
                    "排队等待下发");
        }
        return viewMapper.toTaskView(task, executions, true);
    }

    private TaskExecutionEntity newExecution(TaskEntity task, ResolvedTarget target, Instant now) {
        TaskExecutionEntity exec = new TaskExecutionEntity();
        exec.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
        exec.setTaskId(task.getId());
        exec.setAgentId(target.agentId());
        exec.setAgentTag(target.displayTag());
        exec.setTargetRaw(target.raw());
        exec.setStatus(ExecutionStatus.PENDING);
        exec.setAttempt(1);
        exec.setCreatedAt(now);
        exec.setUpdatedAt(now);
        return exec;
    }

    private String defaultName(String command) {
        String flat = command.replaceAll("\\s+", " ").trim();
        return flat.length() > 60 ? flat.substring(0, 60) + "…" : flat;
    }

    public record ResolvedTarget(String raw, String agentId, String displayTag) {
    }

    /** tag or agentId in, frozen agentId out; resolution happens once, at ingest. */
    @Transactional(readOnly = true)
    public List<ResolvedTarget> resolveTargets(List<JsonNode> targets) {
        if (targets == null || targets.isEmpty()) {
            throw ApiException.badRequest("targets 不能为空");
        }
        Map<String, ResolvedTarget> resolved = new LinkedHashMap<>();
        for (JsonNode node : targets) {
            String raw;
            String type = null;
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isTextual()) {
                raw = node.asText();
            } else if (node.isObject()) {
                raw = Json.text(node, "value", "agentId", "tag", "displayTag", "id");
                type = Json.text(node, "type", "kind");
            } else {
                raw = node.asText();
            }
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String key = raw.trim();
            AgentEntity agent = null;
            if (type == null || "agentId".equalsIgnoreCase(type)) {
                agent = agentRepository.findById(key).orElse(null);
            }
            if (agent == null && (type == null || "tag".equalsIgnoreCase(type) || "displayTag".equalsIgnoreCase(type))) {
                agent = agentRepository.findByDisplayTag(key).orElse(null);
            }
            if (agent == null) {
                throw ApiException.badRequest("未知目标: " + key + "（既不是 agentId 也不是 displayTag）");
            }
            resolved.putIfAbsent(agent.getAgentId(),
                    new ResolvedTarget(key, agent.getAgentId(), agent.getDisplayTag()));
        }
        if (resolved.isEmpty()) {
            throw ApiException.badRequest("targets 不能为空");
        }
        return List.copyOf(resolved.values());
    }

    // -------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public Map<String, Object> list(String status, int page, int size, boolean includeExecutions) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, "id"));
        TaskStatus taskStatus = TaskStatus.fromWire(status);
        Page<TaskEntity> tasks = taskStatus == null
                ? taskRepository.findAll(pageable)
                : taskRepository.findByStatus(taskStatus, pageable);

        List<Long> ids = tasks.getContent().stream().map(TaskEntity::getId).toList();
        Map<Long, List<TaskExecutionEntity>> byTask = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (TaskExecutionEntity exec : executionRepository.findByTaskIdInOrderByIdAsc(ids)) {
                byTask.computeIfAbsent(exec.getTaskId(), k -> new ArrayList<>()).add(exec);
            }
        }
        List<TaskView> items = tasks.getContent().stream()
                .map(t -> viewMapper.toTaskView(t, byTask.getOrDefault(t.getId(), List.of()), includeExecutions))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", tasks.getTotalElements());
        result.put("page", tasks.getNumber());
        result.put("size", tasks.getSize());
        return result;
    }

    @Transactional(readOnly = true)
    public TaskView detail(Long taskId) {
        TaskEntity task = requireTask(taskId);
        return viewMapper.toTaskView(task, executionRepository.findByTaskIdOrderByIdAsc(taskId), true);
    }

    public TaskEntity requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("task 不存在: " + taskId));
    }

    // ------------------------------------------------------------------ cancel

    @Transactional
    public Map<String, Object> cancel(Long taskId, String operator) {
        TaskEntity task = requireTask(taskId);
        List<TaskExecutionEntity> executions = executionRepository.findByTaskIdOrderByIdAsc(taskId);
        int affected = 0;
        for (TaskExecutionEntity exec : executions) {
            if (exec.getStatus().isTerminal()) {
                continue;
            }
            dispatchService.requestCancel(exec, "用户取消" + (operator == null ? "" : " by " + operator), true);
            affected++;
        }
        eventService.record(EventService.T_TASK_CANCELED, null, null, taskId,
                "取消任务，影响 " + affected + " 条执行");
        executionService.touchTask(taskId);
        return Map.of("taskId", taskId, "canceled", affected, "status", requireTask(taskId).getStatus().wire());
    }

    // ------------------------------------------------------------------- rerun

    @Transactional
    public TaskView rerun(Long taskId, RerunRequest request) {
        TaskEntity task = requireTask(taskId);
        String mode = request == null || request.getMode() == null ? "new" : request.getMode().trim().toLowerCase();
        if (!"inplace".equals(mode) && !"new".equals(mode)) {
            throw ApiException.badRequest("mode 只支持 inplace 或 new");
        }
        List<TaskExecutionEntity> executions = executionRepository.findByTaskIdOrderByIdAsc(taskId);
        Set<String> filter = request == null || request.getTargets() == null
                ? Set.of() : new LinkedHashSet<>(request.getTargets());

        List<TaskExecutionEntity> selected = executions.stream()
                .filter(e -> filter.isEmpty() || filter.contains(e.getExecuteId())
                        || filter.contains(e.getAgentId()) || filter.contains(e.getAgentTag()))
                .toList();
        if (selected.isEmpty()) {
            throw ApiException.badRequest("没有可重跑的执行");
        }
        if ("inplace".equals(mode)) {
            return rerunInPlace(task, selected);
        }
        return rerunAsNewTask(task, selected, request == null ? null : request.getOperator());
    }

    private TaskView rerunInPlace(TaskEntity task, List<TaskExecutionEntity> selected) {
        for (TaskExecutionEntity exec : selected) {
            if (!exec.getStatus().isTerminal()) {
                throw ApiException.conflict("执行 " + exec.getExecuteId() + " 仍在进行，无法原地重跑");
            }
        }
        Instant now = Instant.now();
        for (TaskExecutionEntity exec : selected) {
            logRepository.deleteByExecuteId(exec.getExecuteId());
            exec.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
            exec.setStatus(ExecutionStatus.PENDING);
            exec.setSubStatus(null);
            exec.setDispatchToken(null);
            exec.setLeaseExpireAt(null);
            exec.setAcked(false);
            exec.setCancelRequested(false);
            exec.setTimeoutRequested(false);
            exec.setExitCode(null);
            exec.setLastLine(null);
            exec.setReason(null);
            exec.setMatchedRule(null);
            exec.setLogSeq(0);
            exec.setLogMinSeq(0);
            exec.setLogBytes(0);
            exec.setTruncated(false);
            exec.setAttempt(exec.getAttempt() + 1);
            exec.setDispatchedAt(null);
            exec.setStartedAt(null);
            exec.setFinishedAt(null);
            exec.setDisconnectedAt(null);
            exec.setUpdatedAt(now);
            eventService.record(EventService.T_TASK_RERUN, exec.getAgentId(), exec.getExecuteId(), task.getId(),
                    "原地重跑，第 " + exec.getAttempt() + " 次");
        }
        executionRepository.saveAll(selected);
        task.setStatus(TaskStatus.PENDING);
        task.setUpdatedAt(now);
        taskRepository.save(task);
        return viewMapper.toTaskView(task, executionRepository.findByTaskIdOrderByIdAsc(task.getId()), true);
    }

    private TaskView rerunAsNewTask(TaskEntity source, List<TaskExecutionEntity> selected, String operator) {
        Instant now = Instant.now();
        TaskEntity task = new TaskEntity();
        task.setName(source.getName() == null ? "rerun" : source.getName() + " (重跑)");
        task.setCommand(source.getCommand());
        task.setCwd(source.getCwd());
        task.setEnv(source.getEnv());
        task.setConditionConfig(source.getConditionConfig());
        task.setTargets(source.getTargets());
        task.setOperator(operator == null || operator.isBlank() ? source.getOperator() : operator);
        task.setTimeoutSec(source.getTimeoutSec());
        task.setPriority(source.getPriority());
        task.setQueueOrder(taskRepository.maxQueueOrder() + 1);
        task.setStatus(TaskStatus.PENDING);
        task.setTotalCount(selected.size());
        task.setRerunOf(source.getId());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskRepository.save(task);

        List<TaskExecutionEntity> executions = new ArrayList<>();
        for (TaskExecutionEntity src : selected) {
            String tag = agentRepository.findById(src.getAgentId())
                    .map(AgentEntity::getDisplayTag).orElse(src.getAgentTag());
            executions.add(newExecution(task,
                    new ResolvedTarget(src.getTargetRaw() == null ? src.getAgentId() : src.getTargetRaw(),
                            src.getAgentId(), tag), now));
        }
        executionRepository.saveAll(executions);
        eventService.record(EventService.T_TASK_RERUN, null, null, task.getId(),
                "由任务 " + source.getId() + " 重跑生成，目标 " + executions.size() + " 台");
        return viewMapper.toTaskView(task, executions, true);
    }

    // ----------------------------------------------------------------- reorder

    /** Only tasks that have not started can be reordered; running work is never preempted. */
    @Transactional
    public Map<String, Object> reorder(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw ApiException.badRequest("taskIds 不能为空");
        }
        List<TaskEntity> tasks = new ArrayList<>();
        for (Long id : taskIds) {
            TaskEntity task = requireTask(id);
            if (task.getStatus() != TaskStatus.PENDING) {
                throw ApiException.conflict("任务 " + id + " 不是 pending，无法排序");
            }
            long active = executionRepository.countByTaskIdAndStatusIn(id,
                    List.of(ExecutionStatus.DISPATCHING, ExecutionStatus.RUNNING));
            if (active > 0) {
                throw ApiException.conflict("任务 " + id + " 已有执行在跑，无法排序");
            }
            tasks.add(task);
        }
        Instant now = Instant.now();
        long base = 1;
        for (TaskEntity task : tasks) {
            task.setQueueOrder(base++);
            task.setUpdatedAt(now);
        }
        taskRepository.saveAll(tasks);

        // keep every other pending task behind the explicitly ordered ones
        long tail = base;
        List<TaskEntity> others = taskRepository.findByStatusOrderByQueueOrderAscIdAsc(TaskStatus.PENDING);
        List<TaskEntity> shifted = new ArrayList<>();
        for (TaskEntity task : others) {
            if (taskIds.contains(task.getId())) {
                continue;
            }
            task.setQueueOrder(tail++);
            task.setUpdatedAt(now);
            shifted.add(task);
        }
        if (!shifted.isEmpty()) {
            taskRepository.saveAll(shifted);
        }
        eventService.record(EventService.T_TASK_REORDER, null, null, null,
                "队列重排: " + taskIds);
        return Map.of("ordered", taskIds, "count", taskIds.size());
    }
}
