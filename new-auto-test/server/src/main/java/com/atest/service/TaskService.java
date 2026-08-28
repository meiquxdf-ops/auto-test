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
import com.atest.repo.OpenRequestRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.web.dto.BatchCreateTaskRequest;
import com.atest.web.dto.CreateTaskRequest;
import com.atest.web.dto.RerunRequest;
import com.atest.web.dto.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final OpenRequestRepository openRequestRepository;
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
                       OpenRequestRepository openRequestRepository,
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
        this.openRequestRepository = openRequestRepository;
        this.judgeService = judgeService;
        this.executionService = executionService;
        this.dispatchService = dispatchService;
        this.eventService = eventService;
        this.viewMapper = viewMapper;
    }

    // ------------------------------------------------------------------ create

    public static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    public static final int BATCH_MAX_ITEMS = 100;
    public static final int REQUEST_ID_QUERY_CAP = 200;

    /**
     * Tail of the queue, handed out atomically. {@code maxQueueOrder() + 1} inside the creation
     * transaction is racy: two concurrent creates read the same max before either commits and
     * both store the same position (seen live: 15 parallel POSTs produced two duplicate pairs).
     * Seeded from the DB once; a rollback leaves a gap, which is harmless, and the reorder API
     * only ever rebases pending tasks downwards so the counter stays past every stored value.
     */
    private final java.util.concurrent.atomic.AtomicLong queueTail = new java.util.concurrent.atomic.AtomicLong(-1);

    private long nextQueueOrder() {
        if (queueTail.get() < 0) {
            synchronized (queueTail) {
                if (queueTail.get() < 0) {
                    queueTail.set(taskRepository.maxQueueOrder());
                }
            }
        }
        return queueTail.incrementAndGet();
    }

    @Transactional
    public TaskView create(CreateTaskRequest request) {
        String requestId = normalizeRequestId(request.getRequestId(), false);
        String callbackUrl = normalizeCallbackUrl(request.getCallbackUrl());
        PreparedTask prepared = prepareTask(request.getName(), request.getCommand(), request.getCwd(),
                request.getEnv(), request.getTargets(), request.getConditionConfig(), request.getOperator(),
                request.getTimeoutSec(), request.getPriority(), null);
        // the ops console / playground never has to type one: a blank requestId is minted here
        // and returned on the TaskView, so the caller can still query / correlate by it
        if (requestId == null) {
            requestId = claimGeneratedRequestId("auto");
        } else {
            claimRequestId(requestId, "single");
        }
        return persistTask(prepared, requestId, callbackUrl, Instant.now());
    }

    /**
     * Open-API batch: one HTTP request creating several tasks (different commands / targets)
     * grouped by one requestId. Partial success per item: an invalid item (empty command, unknown
     * target, bad fields) only rejects that item and lands in {@code errors[{index,message}]},
     * the valid items are still created. A missing / malformed / duplicate requestId rejects the
     * whole call before any item is looked at. When EVERY item fails, nothing is persisted and the
     * requestId stays free, so the caller can fix the payload and retry with the same key.
     */
    @Transactional
    public Map<String, Object> createBatch(BatchCreateTaskRequest request) {
        String requestId = normalizeRequestId(request.getRequestId(), true);
        String callbackUrl = normalizeCallbackUrl(request.getCallbackUrl());
        List<BatchCreateTaskRequest.Item> items = request.getItems();
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("items 不能为空");
        }
        if (items.size() > BATCH_MAX_ITEMS) {
            throw ApiException.badRequest("items 最多 " + BATCH_MAX_ITEMS + " 条，实际 " + items.size() + " 条");
        }
        List<PreparedTask> prepared = new ArrayList<>(items.size());
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            BatchCreateTaskRequest.Item item = items.get(i);
            try {
                if (item == null) {
                    throw ApiException.badRequest("item 不能为空");
                }
                // an item with any bad target is rejected whole: never a task with a subset of targets
                prepared.add(prepareTask(item.getName(), item.getCommand(), item.getCwd(), item.getEnv(),
                        item.getTargets(), item.getConditionConfig(), item.getOperator(), item.getTimeoutSec(),
                        null, null));
            } catch (ApiException e) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("index", i);
                error.put("message", e.getMessage());
                errors.add(error);
            }
        }
        if (prepared.isEmpty()) {
            // zero successes: don't consume the requestId, otherwise its query stays empty forever
            throw ApiException.badRequest("items 全部校验失败，requestId 未占用，可修正后原样重试")
                    .withExtra("requestId", requestId)
                    .withExtra("errors", errors);
        }
        claimRequestId(requestId, "batch");
        Instant now = Instant.now();
        List<TaskView> tasks = new ArrayList<>(prepared.size());
        for (PreparedTask p : prepared) {
            tasks.add(persistTask(p, requestId, callbackUrl, now));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("tasks", tasks);
        result.put("errors", errors);
        return result;
    }

    /** Everything validated / resolved, nothing written yet. */
    private record PreparedTask(String name, String command, String cwd, String envJson, String conditionJson,
                                List<ResolvedTarget> targets, String operator, int timeoutSec, int priority) {
    }

    private PreparedTask prepareTask(String name, String command, String cwd, Map<String, String> env,
                                     List<JsonNode> targets, JsonNode conditionConfig, String operator,
                                     Integer timeoutSec, Integer priority, String errorPrefix) {
        String prefix = errorPrefix == null ? "" : errorPrefix;
        if (command == null || command.isBlank()) {
            throw ApiException.badRequest(prefix + "command 不能为空");
        }
        String conditionJson = conditionConfig == null || conditionConfig.isNull()
                ? null : Json.write(conditionConfig);
        try {
            judgeService.validate(conditionJson);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(prefix + e.getMessage());
        }
        List<ResolvedTarget> resolved;
        try {
            resolved = resolveTargets(targets);
        } catch (ApiException e) {
            throw prefix.isEmpty() ? e : ApiException.badRequest(prefix + e.getMessage());
        }
        return new PreparedTask(
                name == null || name.isBlank() ? defaultName(command) : name.trim(),
                command,
                cwd,
                env == null || env.isEmpty() ? null : Json.write(env),
                conditionJson,
                resolved,
                operator,
                timeoutSec == null || timeoutSec <= 0 ? props.getDispatch().getDefaultTimeoutSec() : timeoutSec,
                priority == null ? 0 : priority);
    }

    private TaskView persistTask(PreparedTask prepared, String requestId, String callbackUrl, Instant now) {
        TaskEntity task = new TaskEntity();
        task.setName(prepared.name());
        task.setCommand(prepared.command());
        task.setCwd(prepared.cwd());
        task.setEnv(prepared.envJson());
        task.setConditionConfig(prepared.conditionJson());
        task.setTargets(Json.write(prepared.targets().stream().map(ResolvedTarget::raw).toList()));
        task.setOperator(prepared.operator());
        task.setTimeoutSec(prepared.timeoutSec());
        task.setPriority(prepared.priority());
        task.setQueueOrder(nextQueueOrder());
        task.setStatus(TaskStatus.PENDING);
        task.setTotalCount(prepared.targets().size());
        task.setRequestId(requestId);
        task.setCallbackUrl(callbackUrl);
        task.setCallbackStatus(callbackUrl == null ? CallbackStatus.NONE : CallbackStatus.PENDING);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskRepository.save(task);

        List<TaskExecutionEntity> executions = new ArrayList<>();
        for (ResolvedTarget target : prepared.targets()) {
            executions.add(newExecution(task, target, now));
        }
        executionRepository.saveAll(executions);

        eventService.record(EventService.T_TASK_CREATED, null, null, task.getId(),
                "创建任务 " + task.getName() + "，目标 " + prepared.targets().size() + " 台，operator="
                        + task.getOperator() + (requestId == null ? "" : "，requestId=" + requestId));
        for (TaskExecutionEntity exec : executions) {
            eventService.record(EventService.T_CREATED, exec.getAgentId(), exec.getExecuteId(), task.getId(),
                    "排队等待下发");
        }
        return viewMapper.toTaskView(task, executions, true);
    }

    /** trim + charset check + global uniqueness; blank means "mint one for me" (batch excepted). */
    private String normalizeRequestId(String requestId, boolean required) {
        String v = requestId == null ? "" : requestId.trim();
        if (v.isEmpty()) {
            if (required) {
                throw ApiException.badRequest("requestId 不能为空");
            }
            return null;
        }
        if (!REQUEST_ID_PATTERN.matcher(v).matches()) {
            throw ApiException.badRequest("requestId 只允许字母、数字和 . _ -，长度 1-64: " + v);
        }
        if (openRequestRepository.existsById(v)) {
            throw ApiException.conflict("requestId 已存在: " + v);
        }
        return v;
    }

    /**
     * Consumes a requestId inside the creating transaction. The registry's primary key is the
     * real uniqueness guarantee: when two calls race past the exists-check, the second INSERT
     * dies on the constraint and still surfaces as the documented 409.
     */
    private void claimRequestId(String requestId, String source) {
        try {
            openRequestRepository.claim(requestId, source, Instant.now());
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("requestId 已存在: " + requestId);
        }
    }

    /** A server-minted key: a UUID (hyphens are inside the allowed charset, 36 <= 64 chars). */
    private String claimGeneratedRequestId(String source) {
        String candidate = UUID.randomUUID().toString();
        while (openRequestRepository.existsById(candidate)) {
            candidate = UUID.randomUUID().toString();
        }
        claimRequestId(candidate, source);
        return candidate;
    }

    private String normalizeCallbackUrl(String callbackUrl) {
        String v = callbackUrl == null ? "" : callbackUrl.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() > 1024) {
            throw ApiException.badRequest("callbackUrl 最长 1024 字符");
        }
        URI uri;
        try {
            uri = new URI(v);
        } catch (Exception e) {
            throw ApiException.badRequest("callbackUrl 不是合法 URL: " + v);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw ApiException.badRequest("callbackUrl 只支持 http/https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw ApiException.badRequest("callbackUrl 缺少主机名");
        }
        return v;
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
        Map<Long, Long> attachmentCounts = viewMapper.attachmentCounts(ids);
        List<TaskView> items = tasks.getContent().stream()
                .map(t -> viewMapper.toTaskView(t, byTask.getOrDefault(t.getId(), List.of()), includeExecutions,
                        attachmentCounts.getOrDefault(t.getId(), 0L)))
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

    /** Open query: every task created under one requestId (single or batch), capped at 200. */
    @Transactional(readOnly = true)
    public Map<String, Object> listByRequestId(String requestId, boolean includeExecutions) {
        String v = requestId == null ? "" : requestId.trim();
        if (v.isEmpty()) {
            throw ApiException.badRequest("requestId 不能为空");
        }
        List<TaskEntity> tasks = taskRepository.findByRequestIdOrderByIdAsc(v,
                PageRequest.of(0, REQUEST_ID_QUERY_CAP));
        List<Long> ids = tasks.stream().map(TaskEntity::getId).toList();
        Map<Long, List<TaskExecutionEntity>> byTask = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (TaskExecutionEntity exec : executionRepository.findByTaskIdInOrderByIdAsc(ids)) {
                byTask.computeIfAbsent(exec.getTaskId(), k -> new ArrayList<>()).add(exec);
            }
        }
        Map<Long, Long> attachmentCounts = viewMapper.attachmentCounts(ids);
        List<TaskView> items = tasks.stream()
                .map(t -> viewMapper.toTaskView(t, byTask.getOrDefault(t.getId(), List.of()), includeExecutions,
                        attachmentCounts.getOrDefault(t.getId(), 0L)))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", v);
        result.put("items", items);
        result.put("total", items.size());
        result.put("page", 0);
        result.put("size", REQUEST_ID_QUERY_CAP);
        return result;
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
        // both modes: rerunning while a selected execution is still live is rejected, never
        // silently cloned alongside it — 同一份执行不允许边跑边重跑
        for (TaskExecutionEntity exec : selected) {
            if (!exec.getStatus().isTerminal()) {
                throw ApiException.conflict("执行 " + exec.getExecuteId() + " 仍在进行（"
                        + exec.getStatus().wire() + "），请等待其结束后再重跑");
            }
        }
        if ("inplace".equals(mode)) {
            return rerunInPlace(task, selected);
        }
        return rerunAsNewTask(task, selected, request == null ? null : request.getOperator());
    }

    private TaskView rerunInPlace(TaskEntity task, List<TaskExecutionEntity> selected) {
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
        // the task will reach a terminal state again, so a configured callback re-arms
        if (task.getCallbackUrl() != null) {
            task.setCallbackStatus(CallbackStatus.PENDING);
            task.setCallbackAttempts(0);
            task.setCallbackLastError(null);
            task.setCallbackLastAt(null);
        }
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
        task.setQueueOrder(nextQueueOrder());
        task.setStatus(TaskStatus.PENDING);
        task.setTotalCount(selected.size());
        task.setRerunOf(source.getId());
        // a fresh key, never the source's: requestId groups one create call, not a task lineage
        task.setRequestId(claimGeneratedRequestId("rerun"));
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
