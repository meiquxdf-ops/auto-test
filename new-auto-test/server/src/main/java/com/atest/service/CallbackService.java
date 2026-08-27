package com.atest.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.atest.common.Json;
import com.atest.config.AtestProperties;
import com.atest.domain.CallbackStatus;
import com.atest.domain.TaskEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One-shot result callback for open-API tasks: when a task reaches a terminal state
 * (finished / canceled, i.e. all its executions are done) the full result is POSTed to the
 * caller's callbackUrl. Delivery retries with exponential backoff; the retry is only about the
 * HTTP notification, the shell command is never re-run. The pending -> running claim is a DB
 * compare-and-set, so a task fires at most one callback per terminal transition.
 */
@Slf4j
@Service
public class CallbackService {

    /** callback lastLine cap; full logs (up to 5MB) intentionally never travel in the callback */
    private static final int MAX_LAST_LINE_CHARS = 4096;
    private static final int MAX_ERROR_CHARS = 500;

    private final AtestProperties props;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final EventService eventService;
    private final ExecutorService workExecutor;
    private final TaskScheduler retryScheduler;
    private final HttpClient httpClient;

    public CallbackService(AtestProperties props,
                           TaskRepository taskRepository,
                           TaskExecutionRepository executionRepository,
                           EventService eventService,
                           @Qualifier("agentWorkExecutor") ExecutorService workExecutor,
                           @Qualifier("taskScheduler") TaskScheduler retryScheduler) {
        this.props = props;
        this.taskRepository = taskRepository;
        this.executionRepository = executionRepository;
        this.eventService = eventService;
        this.workExecutor = workExecutor;
        this.retryScheduler = retryScheduler;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getCallback().getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(workExecutor)
                .build();
    }

    /**
     * Called from the task status transition (inside its transaction). The actual send starts
     * only after commit, so a rollback never leaks a callback and the sender sees fresh rows.
     */
    public void onTaskTerminal(Long taskId) {
        if (taskId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(taskId);
                }
            });
        } else {
            submit(taskId);
        }
    }

    /** Requeues callbacks that never got claimed, e.g. the server restarted between commit and send. */
    @Scheduled(fixedDelayString = "${atest.callback.sweep-interval-ms:30000}")
    public void sweepBacklog() {
        try {
            for (Long taskId : taskRepository.findCallbackBacklog(PageRequest.of(0, 50))) {
                submit(taskId);
            }
        } catch (Exception e) {
            log.error("callback backlog sweep failed", e);
        }
    }

    private void submit(Long taskId) {
        workExecutor.execute(() -> {
            try {
                claimAndFire(taskId);
            } catch (Exception e) {
                log.error("callback for task {} failed to start", taskId, e);
            }
        });
    }

    private void claimAndFire(Long taskId) {
        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getCallbackUrl() == null
                || task.getCallbackStatus() != CallbackStatus.PENDING
                || !task.getStatus().isTerminal()) {
            return;
        }
        if (taskRepository.casClaimCallback(taskId, Instant.now()) != 1) {
            return;
        }
        String body = buildPayload(task);
        eventService.record(EventService.T_CALLBACK, null, null, taskId,
                "开始回调 " + safeUrl(task.getCallbackUrl()) + "，任务状态 " + task.getStatus().wire());
        attempt(taskId, task.getCallbackUrl(), body, 1);
    }

    private void attempt(Long taskId, String url, String body, int attemptNo) {
        // an inplace rerun may have reset the callback; a stale retry chain must die silently
        CallbackStatus current = taskRepository.findById(taskId)
                .map(TaskEntity::getCallbackStatus).orElse(null);
        if (current != CallbackStatus.RUNNING) {
            return;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(props.getCallback().getTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            // URL was validated at ingest; treat a bad one as a hard failure, no retry can fix it
            markFailed(taskId, attemptNo, "回调地址无效: " + trimError(e.getMessage()));
            return;
        }
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenCompleteAsync((rsp, err) -> handleResult(taskId, url, body, attemptNo, rsp, err),
                        workExecutor);
    }

    private void handleResult(Long taskId, String url, String body, int attemptNo,
                              HttpResponse<Void> rsp, Throwable err) {
        try {
            if (err == null && rsp != null && rsp.statusCode() / 100 == 2) {
                taskRepository.finishCallback(taskId, CallbackStatus.SUCCESS, attemptNo, null, Instant.now());
                eventService.record(EventService.T_CALLBACK, null, null, taskId,
                        "回调成功（第 " + attemptNo + " 次，HTTP " + rsp.statusCode() + "）");
                log.info("callback ok task={} attempt={} url={}", taskId, attemptNo, safeUrl(url));
                return;
            }
            String error = err != null
                    ? trimError(rootMessage(err))
                    : "HTTP " + (rsp == null ? "?" : rsp.statusCode());
            if (attemptNo >= props.getCallback().getMaxAttempts()) {
                markFailed(taskId, attemptNo, error);
                return;
            }
            taskRepository.recordCallbackAttempt(taskId, attemptNo, error, Instant.now());
            long delayMs = props.getCallback().getBackoffBaseMs() << (attemptNo - 1);
            log.warn("callback attempt {} for task {} failed ({}), retry in {}ms, url={}",
                    attemptNo, taskId, error, delayMs, safeUrl(url));
            retryScheduler.schedule(() -> attempt(taskId, url, body, attemptNo + 1),
                    Instant.now().plusMillis(delayMs));
        } catch (Exception e) {
            log.error("callback bookkeeping for task {} failed", taskId, e);
        }
    }

    private void markFailed(Long taskId, int attempts, String error) {
        taskRepository.finishCallback(taskId, CallbackStatus.FAILED, attempts, error, Instant.now());
        eventService.record(EventService.T_CALLBACK, null, null, taskId,
                "回调失败，已放弃（共 " + attempts + " 次）: " + error);
        log.warn("callback gave up task={} attempts={} lastError={}", taskId, attempts, error);
    }

    /** Full useful result, but never the raw logs: those stay behind the log API. */
    private String buildPayload(TaskEntity task) {
        List<TaskExecutionEntity> executions = executionRepository.findByTaskIdOrderByIdAsc(task.getId());
        Map<String, Long> counts = new LinkedHashMap<>();
        List<Map<String, Object>> execViews = new ArrayList<>();
        for (TaskExecutionEntity e : executions) {
            counts.merge(e.getStatus().wire(), 1L, Long::sum);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("executeId", e.getExecuteId());
            view.put("agentId", e.getAgentId());
            view.put("agentTag", e.getAgentTag());
            view.put("status", e.getStatus().wire());
            view.put("exitCode", e.getExitCode());
            view.put("lastLine", clip(e.getLastLine()));
            view.put("reason", e.getReason());
            view.put("matchedRule", e.getMatchedRule());
            view.put("attempt", e.getAttempt());
            view.put("createdAt", iso(e.getCreatedAt()));
            view.put("startedAt", iso(e.getStartedAt()));
            view.put("finishedAt", iso(e.getFinishedAt()));
            execViews.add(view);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "task.terminal");
        payload.put("taskId", task.getId());
        payload.put("requestId", task.getRequestId());
        payload.put("name", task.getName());
        payload.put("status", task.getStatus().wire());
        payload.put("operator", task.getOperator());
        payload.put("totalCount", task.getTotalCount());
        payload.put("statusCounts", counts);
        payload.put("createdAt", iso(task.getCreatedAt()));
        payload.put("finishedAt", iso(task.getUpdatedAt()));
        payload.put("executions", execViews);
        payload.put("ts", System.currentTimeMillis());
        return Json.write(payload);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String clip(String line) {
        if (line == null || line.length() <= MAX_LAST_LINE_CHARS) {
            return line;
        }
        return line.substring(0, MAX_LAST_LINE_CHARS) + "…";
    }

    private static String rootMessage(Throwable err) {
        Throwable cause = err;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg == null || msg.isBlank() ? cause.getClass().getSimpleName() : msg;
    }

    private static String trimError(String error) {
        if (error == null) {
            return "unknown error";
        }
        return error.length() > MAX_ERROR_CHARS ? error.substring(0, MAX_ERROR_CHARS) : error;
    }

    /** Log-safe form: query string and userinfo may carry caller secrets, they never hit the log. */
    static String safeUrl(String url) {
        try {
            URI uri = URI.create(url);
            StringBuilder sb = new StringBuilder();
            sb.append(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0) {
                sb.append(':').append(uri.getPort());
            }
            if (uri.getRawPath() != null) {
                sb.append(uri.getRawPath());
            }
            return sb.toString();
        } catch (Exception e) {
            return "<invalid-url>";
        }
    }
}
