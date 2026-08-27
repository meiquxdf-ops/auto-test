package com.hjmicro.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import com.hjmicro.agent.service.TaskHandlerService;
import com.hjmicro.domain.dto.TaskExecuteDTO;
import com.hjmicro.domain.dto.TaskExecuteInfo;
import com.hjmicro.netty.SerializedSendServer;
import com.hjmicro.server.service.TaskRpcService;
import org.apache.log4j.Logger;

public class TaskHandlerServiceImpl implements TaskHandlerService {

    private static final Logger logger = Logger.getLogger(TaskHandlerServiceImpl.class);
    private static final LogBatcher LOG_BATCHER = new LogBatcher();
    private static final Object TASK_CONTROL_LOCK = new Object();
    // 任务线程池
    private static ThreadPoolExecutor taskThreadPoolExecutor =
            newTaskExecutor();

    private static void pushLog(TaskExecuteInfo taskExecuteInfo) {
        // 创建副本，避免异步发送时对象被修改
        TaskExecuteInfo copy = new TaskExecuteInfo();
        copy.setTaskId(taskExecuteInfo.getTaskId());
        copy.setExecuteId(taskExecuteInfo.getExecuteId());
        copy.setIpAddress(taskExecuteInfo.getIpAddress());
        copy.setOutLine(taskExecuteInfo.getOutLine());
        copy.setResult(taskExecuteInfo.getResult());
        copy.setFinished(taskExecuteInfo.getFinished());
        copy.setSuccess(taskExecuteInfo.getSuccess());
        copy.setFirst(taskExecuteInfo.getFirst());
        copy.setCanceled(taskExecuteInfo.getCanceled());
        copy.setDispatchToken(taskExecuteInfo.getDispatchToken());

        // 异步批量发送日志，避免逐行直发导致的拥塞与任务阻塞
        LOG_BATCHER.enqueue(copy);
    }

    @Override
    public List<String> listPathFile() {
        return new ArrayList<>(Arrays.asList("123123", "1231231"));
    }

    //获取正在执行的线程数
    public static int getActiveCount() {
        if (logger.isDebugEnabled()) {
            logger.debug("[Task] 线程池状态: active=" + taskThreadPoolExecutor.getActiveCount()
                    + ", poolSize=" + taskThreadPoolExecutor.getPoolSize()
                    + ", queueSize=" + taskThreadPoolExecutor.getQueue().size());
        }
        return taskThreadPoolExecutor.getActiveCount();
    }


    // 用于存储任务的Future引用
    private static CopyOnWriteArrayList<Future<?>> taskFutures = new CopyOnWriteArrayList<>();

    // 用于存储正在运行的进程引用
    private static CopyOnWriteArrayList<Process> runningProcesses = new CopyOnWriteArrayList<>();

    private static final String AGENT_SESSION_ID = UUID.randomUUID().toString();
    private static final AtomicLong STATE_VERSION = new AtomicLong(0);
    private static final Map<Long, Future<?>> taskFutureMap = new ConcurrentHashMap<>();
    private static final Map<Long, Process> runningProcessMap = new ConcurrentHashMap<>();
    private static final Map<Long, String> dispatchTokenMap = new ConcurrentHashMap<>();
    private static final Set<Long> canceledExecutions = ConcurrentHashMap.newKeySet();
    private static volatile Long runningExecuteId;
    private static volatile String runningDispatchToken;

    private static ThreadPoolExecutor newTaskExecutor() {
        return new ThreadPoolExecutor(
                3, 5, 1, TimeUnit.MINUTES, new ArrayBlockingQueue<>(10)
        );
    }

    @Override
    public Boolean stopTask() {
        synchronized (TASK_CONTROL_LOCK) {
            int runningCount = taskFutures.size();
            int processCount = runningProcesses.size();
            int queuedCount = taskThreadPoolExecutor.getQueue().size();
            logger.warn("[Task] 停止所有任务: futures=" + runningCount
                    + ", processes=" + processCount
                    + ", queued=" + queuedCount
                    + ", runningExecuteId=" + runningExecuteId);
            AgentEventLogger.event(
                    "task_stop_requested",
                    "WARN",
                    null,
                    runningExecuteId,
                    runningDispatchToken,
                    null,
                    null,
                    "stop all running tasks requested",
                    "futures=" + runningCount + ", processes=" + processCount + ", queued=" + queuedCount,
                    null,
                    null
            );

            try {
                canceledExecutions.addAll(taskFutureMap.keySet());
                if (runningExecuteId != null) {
                    canceledExecutions.add(runningExecuteId);
                }

                int killedCount = 0;
                for (Process process : runningProcesses) {
                    if (destroyProcessTree(process)) {
                        killedCount++;
                    }
                }

                int cancelledCount = 0;
                for (Future<?> future : taskFutures) {
                    if (future.cancel(true)) {
                        cancelledCount++;
                    }
                }

                List<Runnable> droppedTasks = taskThreadPoolExecutor.shutdownNow();
                taskThreadPoolExecutor = newTaskExecutor();
                runningProcesses.clear();
                taskFutures.clear();
                taskFutureMap.clear();
                runningProcessMap.clear();
                dispatchTokenMap.clear();
                clearAllRunningState();

                logger.warn("[Task] 任务停止完成: killedProcesses=" + killedCount
                        + ", cancelledFutures=" + cancelledCount
                        + ", droppedQueuedTasks=" + droppedTasks.size());
                AgentEventLogger.event(
                        "task_stop_completed",
                        "WARN",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "stop all running tasks completed",
                        "killedProcesses=" + killedCount
                                + ", cancelledFutures=" + cancelledCount
                                + ", droppedQueuedTasks=" + droppedTasks.size(),
                        null,
                        null
                );
                return true;
            } catch (Exception e) {
                logger.error("[Task] 停止任务异常", e);
                AgentEventLogger.error("task_stop_failed", "stop all running tasks failed", e);
                return false;
            }
        }
    }

    @Override
    public Boolean cancelTask(Long executeId, String dispatchToken) {
        if (executeId == null) {
            AgentEventLogger.warn("task_cancel_rejected", "cancel rejected: executeId is null");
            return Boolean.FALSE;
        }
        String currentToken = dispatchTokenMap.get(executeId);
        if (dispatchToken != null && currentToken != null && !Objects.equals(dispatchToken, currentToken)) {
            logger.warn("[Task] 取消请求 token 不匹配: executeId=" + executeId);
            AgentEventLogger.event(
                    "task_cancel_rejected",
                    "WARN",
                    null,
                    executeId,
                    dispatchToken,
                    null,
                    null,
                    "cancel rejected: dispatch token mismatch",
                    "currentToken=" + currentToken,
                    null,
                    null
            );
            return Boolean.FALSE;
        }

        canceledExecutions.add(executeId);
        AgentEventLogger.event(
                "task_cancel_requested",
                "WARN",
                null,
                executeId,
                dispatchToken,
                null,
                null,
                "cancel task requested",
                null,
                null,
                null
        );
        Process process = runningProcessMap.get(executeId);
        if (process != null) {
            destroyProcessTree(process);
        }

        Future<?> future = taskFutureMap.get(executeId);
        if (future != null) {
            future.cancel(true);
        }
        logger.info("[Task] 已请求取消: executeId=" + executeId);
        return Boolean.TRUE;
    }

    private static boolean destroyProcessTree(Process process) {
        if (process == null) {
            return false;
        }
        boolean killed = false;
        try {
            ProcessHandle handle = process.toHandle();
            handle.descendants()
                    .sorted((left, right) -> Long.compare(right.pid(), left.pid()))
                    .forEach(child -> {
                        try {
                            child.destroyForcibly();
                        } catch (Exception e) {
                            logger.warn("[Task] 终止子进程失败: pid=" + child.pid() + ", error=" + e.getMessage());
                        }
                    });
            if (process.isAlive()) {
                process.destroyForcibly();
                killed = true;
            }
            try {
                process.waitFor(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            logger.info("[Task] 已强制终止进程树: pid=" + handle.pid());
            AgentEventLogger.event(
                    "task_process_tree_killed",
                    "WARN",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "process tree killed",
                    "rootPid=" + handle.pid() + ", descendants=" + handle.descendants().count(),
                    null,
                    null
            );
        } catch (Exception e) {
            logger.warn("[Task] 终止进程树失败: " + e.getMessage());
            AgentEventLogger.error("task_process_tree_kill_failed", "failed to kill process tree", e);
        }
        return killed;
    }

    @Override
    public Boolean restartAgent() {
        logger.warn("[Agent] 收到重启请求，先停止当前任务，然后退出进程");
        AgentEventLogger.warn("agent_restart_requested", "agent restart requested");
        stopTask();
        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            logger.warn("[Agent] 进程即将强制退出，等待外部守护进程拉起");
            AgentEventLogger.event(
                    "agent_exit_planned",
                    "WARN",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "agent process halt planned",
                    "exitCode=20",
                    null,
                    null
            );
            Runtime.getRuntime().halt(20);
        }, "agent-restart");
        restartThread.setDaemon(false);
        restartThread.start();
        return Boolean.TRUE;
    }



    @Override
    public Boolean doTask(TaskExecuteDTO taskExecuteDTO) {
        String command = taskExecuteDTO.getExecutableFilePath();
        String shortCmd = command.length() > 80 ? command.substring(0, 80) + "..." : command;
        logger.info("[Task] 收到: id=" + taskExecuteDTO.getTaskId() + ", cmd=" + shortCmd);
        AgentEventLogger.event(
                "task_received",
                "INFO",
                taskExecuteDTO.getTaskId(),
                taskExecuteDTO.getExecuteId(),
                taskExecuteDTO.getDispatchToken(),
                taskExecuteDTO.getRequestId(),
                command,
                "task received",
                null,
                null,
                null
        );

        Future<?> future;
        synchronized (TASK_CONTROL_LOCK) {
            future = taskThreadPoolExecutor.submit(() -> {
            long startTime = System.currentTimeMillis();

            // 执行任务，这里需要放到
            String fullCommand = taskExecuteDTO.getExecutableFilePath(); // 替换为你的可执行文件路径
            //拼接 echo $?
            fullCommand = fullCommand + " ;";

            // 创建一个列表，包含"bash", "-c", 和你的命令
            List<String> commands = new ArrayList<>();
            commands.add("bash");
            commands.add("-c");
            commands.add(fullCommand);

            // 创建ProcessBuilder对象，传入你的命令
            ProcessBuilder processBuilder = new ProcessBuilder(commands);
            // 重定向标准错误流到标准输出流，这样我们可以通过获取标准输出流来得到错误输出
            processBuilder.redirectErrorStream(true);
            // 开始执行命令
            Process process = null;
            TaskExecuteInfo taskExecuteInfo = new TaskExecuteInfo();
            taskExecuteInfo.setTaskId(taskExecuteDTO.getTaskId());
            taskExecuteInfo.setExecuteId(taskExecuteDTO.getExecuteId());
            taskExecuteInfo.setIpAddress(taskExecuteDTO.getIpAddress());
            taskExecuteInfo.setDispatchToken(taskExecuteDTO.getDispatchToken());
            taskExecuteInfo.setFinished(false);
            taskExecuteInfo.setSuccess(true);
            taskExecuteInfo.setFirst(true);
            int lineCount = 0;
            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Task was interrupted before process start");
                }
                markRunning(taskExecuteDTO.getExecuteId(), taskExecuteDTO.getDispatchToken());
                if (logger.isDebugEnabled()) {
                    logger.debug("[Task] 开始执行: taskId=" + taskExecuteDTO.getTaskId()
                            + ", executeId=" + taskExecuteDTO.getExecuteId()
                            + ", command=" + fullCommand);
                }

                process = processBuilder.start();
                // 将进程添加到运行列表，以便可以被停止
                runningProcesses.add(process);
                runningProcessMap.put(taskExecuteDTO.getExecuteId(), process);
                AgentEventLogger.event(
                        "task_started",
                        "INFO",
                        taskExecuteDTO.getTaskId(),
                        taskExecuteDTO.getExecuteId(),
                        taskExecuteDTO.getDispatchToken(),
                        taskExecuteDTO.getRequestId(),
                        taskExecuteDTO.getExecutableFilePath(),
                        "task process started",
                        "pid=" + process.pid(),
                        null,
                        null
                );
                if (Thread.currentThread().isInterrupted()) {
                    destroyProcessTree(process);
                    throw new InterruptedException("Task was interrupted after process start");
                }

                // 获取输入流，即命令的输出内容
                InputStream inputStream = process.getInputStream();
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(inputStream));
                String line;
                // 开始标记
                pushLog(taskExecuteInfo);
                taskExecuteInfo.setFirst(false);
                while ((line = reader.readLine()) != null) {
                    // 检查线程是否被中断
                    if (Thread.currentThread().isInterrupted()) {
                        logger.info("[Task] 任务被中断: taskId=" + taskExecuteDTO.getTaskId());
                        break;
                    }
                    if (logger.isDebugEnabled()) {
                        logger.debug("[Task] 输出: " + line);
                    }
                    lineCount++;
                    taskExecuteInfo.setOutLine(line);
                    taskExecuteInfo.setResult(line);
                    pushLog(taskExecuteInfo);
                }
                int exitCode = process.waitFor();
                // 结束标记
                taskExecuteInfo.setFinished(true);
                taskExecuteInfo.setCanceled(canceledExecutions.contains(taskExecuteDTO.getExecuteId()));
                taskExecuteInfo.setSuccess(exitCode == 0 && !Boolean.TRUE.equals(taskExecuteInfo.getCanceled()));
                long duration = System.currentTimeMillis() - startTime;
                AgentEventLogger.event(
                        "task_finished",
                        Boolean.TRUE.equals(taskExecuteInfo.getSuccess()) ? "INFO" : "WARN",
                        taskExecuteDTO.getTaskId(),
                        taskExecuteDTO.getExecuteId(),
                        taskExecuteDTO.getDispatchToken(),
                        taskExecuteDTO.getRequestId(),
                        taskExecuteDTO.getExecutableFilePath(),
                        "task finished",
                        "exitCode=" + exitCode
                                + ", success=" + taskExecuteInfo.getSuccess()
                                + ", canceled=" + taskExecuteInfo.getCanceled()
                                + ", result=" + taskExecuteInfo.getResult(),
                        duration,
                        null
                );
                //获取最后一行设置为result
                taskExecuteInfo.setOutLine(null);
                if (taskExecuteInfo.getResult() == null) {
                    taskExecuteInfo.setResult(String.valueOf(exitCode));
                }
                pushLog(taskExecuteInfo);

                String resultSnippet = taskExecuteInfo.getResult();
                resultSnippet = resultSnippet != null && resultSnippet.length() > 50
                        ? resultSnippet.substring(0, 50) + "..." : resultSnippet;
                logger.info("[Task] 完成: id=" + taskExecuteDTO.getTaskId()
                        + ", lines=" + lineCount
                        + ", time=" + formatDuration(duration)
                        + ", result=" + resultSnippet);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                boolean canceled = canceledExecutions.contains(taskExecuteDTO.getExecuteId())
                        || Thread.currentThread().isInterrupted();
                if (canceled) {
                    logger.warn("[Task] 任务已中止: taskId=" + taskExecuteDTO.getTaskId()
                            + ", executeId=" + taskExecuteDTO.getExecuteId()
                            + ", duration=" + formatDuration(duration));
                    AgentEventLogger.event(
                            "task_finished",
                            "WARN",
                            taskExecuteDTO.getTaskId(),
                            taskExecuteDTO.getExecuteId(),
                            taskExecuteDTO.getDispatchToken(),
                            taskExecuteDTO.getRequestId(),
                            taskExecuteDTO.getExecutableFilePath(),
                            "task canceled by agent stop",
                            null,
                            duration,
                            null
                    );
                } else {
                    logger.error("[Task] 执行异常: taskId=" + taskExecuteDTO.getTaskId()
                            + ", executeId=" + taskExecuteDTO.getExecuteId()
                            + ", error=" + e.getMessage()
                            + ", duration=" + formatDuration(duration), e);
                    AgentEventLogger.event(
                            "task_failed",
                            "ERROR",
                            taskExecuteDTO.getTaskId(),
                            taskExecuteDTO.getExecuteId(),
                            taskExecuteDTO.getDispatchToken(),
                            taskExecuteDTO.getRequestId(),
                            taskExecuteDTO.getExecutableFilePath(),
                            "task execution failed",
                            null,
                            duration,
                            e
                    );
                }

                taskExecuteInfo.setSuccess(false);
                taskExecuteInfo.setFinished(true);
                taskExecuteInfo.setCanceled(canceled);
                taskExecuteInfo.setOutLine(canceled ? "Task canceled by agent stop" : e.getMessage());
                pushLog(taskExecuteInfo);
            } finally {
                // 任务完成后从运行列表中移除进程
                if (process != null) {
                    runningProcesses.remove(process);
                    if (process.isAlive()) {
                        destroyProcessTree(process);
                    }
                }
                runningProcessMap.remove(taskExecuteDTO.getExecuteId());
                taskFutureMap.remove(taskExecuteDTO.getExecuteId());
                dispatchTokenMap.remove(taskExecuteDTO.getExecuteId());
                canceledExecutions.remove(taskExecuteDTO.getExecuteId());
                clearRunning(taskExecuteDTO.getExecuteId(), taskExecuteDTO.getDispatchToken());
                taskFutures.removeIf(Future::isDone);
            }
            });

            taskFutures.add(future);
            taskFutureMap.put(taskExecuteDTO.getExecuteId(), future);
            dispatchTokenMap.put(taskExecuteDTO.getExecuteId(), taskExecuteDTO.getDispatchToken());
        }

        if (logger.isDebugEnabled()) {
            logger.debug("[Task] 已提交: id=" + taskExecuteDTO.getTaskId()
                    + ", active=" + taskThreadPoolExecutor.getActiveCount()
                    + ", queued=" + taskThreadPoolExecutor.getQueue().size());
        }
        AgentEventLogger.event(
                "task_submitted",
                "INFO",
                taskExecuteDTO.getTaskId(),
                taskExecuteDTO.getExecuteId(),
                taskExecuteDTO.getDispatchToken(),
                taskExecuteDTO.getRequestId(),
                taskExecuteDTO.getExecutableFilePath(),
                "task submitted to executor",
                "active=" + taskThreadPoolExecutor.getActiveCount()
                        + ", queued=" + taskThreadPoolExecutor.getQueue().size(),
                null,
                null
        );
        return Boolean.TRUE;
    }

    private static synchronized void markRunning(Long executeId, String dispatchToken) {
        runningExecuteId = executeId;
        runningDispatchToken = dispatchToken;
        STATE_VERSION.incrementAndGet();
    }

    private static synchronized void clearRunning(Long executeId, String dispatchToken) {
        if (Objects.equals(runningExecuteId, executeId)
                && Objects.equals(runningDispatchToken, dispatchToken)) {
            runningExecuteId = null;
            runningDispatchToken = null;
            STATE_VERSION.incrementAndGet();
        }
    }

    private static synchronized void clearAllRunningState() {
        if (runningExecuteId != null || runningDispatchToken != null) {
            runningExecuteId = null;
            runningDispatchToken = null;
            STATE_VERSION.incrementAndGet();
        }
    }

    public static String getAgentSessionId() {
        return AGENT_SESSION_ID;
    }

    public static long getStateVersion() {
        return STATE_VERSION.get();
    }

    public static Long getRunningExecuteId() {
        return runningExecuteId;
    }

    public static String getRunningDispatchToken() {
        return runningDispatchToken;
    }

    public static synchronized AgentStateSnapshot getAgentStateSnapshot() {
        return new AgentStateSnapshot(
                AGENT_SESSION_ID,
                STATE_VERSION.get(),
                runningExecuteId,
                runningDispatchToken
        );
    }

    public static class AgentStateSnapshot {
        private final String agentSessionId;
        private final long stateVersion;
        private final Long runningExecuteId;
        private final String runningDispatchToken;

        private AgentStateSnapshot(String agentSessionId, long stateVersion, Long runningExecuteId,
                                   String runningDispatchToken) {
            this.agentSessionId = agentSessionId;
            this.stateVersion = stateVersion;
            this.runningExecuteId = runningExecuteId;
            this.runningDispatchToken = runningDispatchToken;
        }

        public String getAgentSessionId() {
            return agentSessionId;
        }

        public long getStateVersion() {
            return stateVersion;
        }

        public Long getRunningExecuteId() {
            return runningExecuteId;
        }

        public String getRunningDispatchToken() {
            return runningDispatchToken;
        }
    }

    private static String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        } else if (millis < 60000) {
            return String.format("%.1fs", millis / 1000.0);
        } else {
            long minutes = millis / 60000;
            long seconds = (millis % 60000) / 1000;
            return minutes + "m" + seconds + "s";
        }
    }

    private static class LogBatcher {
        private static final int QUEUE_CAPACITY = 5000;
        private static final int MAX_BATCH_EVENTS = 500;
        private static final int MAX_BATCH_LINES = 200;
        private static final int MAX_BATCH_BYTES = 64 * 1024;
        private static final long FLUSH_INTERVAL_MS = 100;
        private static final long DROP_LOG_INTERVAL_MS = 60_000;
        private static final long SEND_FAIL_LOG_INTERVAL_MS = 10_000;

        private final LinkedBlockingDeque<TaskExecuteInfo> queue =
                new LinkedBlockingDeque<>(QUEUE_CAPACITY);
        private final AtomicLong droppedCount = new AtomicLong(0);
        private final AtomicLong lastDropLogAtMs = new AtomicLong(0);
        private final AtomicLong lastSendFailLogAtMs = new AtomicLong(0);
        private final Thread senderThread;

        LogBatcher() {
            senderThread = new Thread(this::run, "task-log-batcher");
            senderThread.setDaemon(true);
            senderThread.start();
        }

        void shutdown(long timeoutMs) {
            senderThread.interrupt();
            try {
                senderThread.join(timeoutMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // 强制刷完剩余队列
            if (!queue.isEmpty()) {
                List<TaskExecuteInfo> remaining = new ArrayList<>();
                queue.drainTo(remaining);
                if (!remaining.isEmpty()) {
                    try {
                        sendBatch(remaining);
                    } catch (Exception e) {
                        logger.warn("[TaskLog] shutdown flush failed: " + e.getMessage());
                    }
                }
            }
        }

        void enqueue(TaskExecuteInfo info) {
            if (info == null) {
                return;
            }

            boolean offered = queue.offerLast(info);
            if (offered) {
                return;
            }

            if (isControl(info)) {
                TaskExecuteInfo dropped = queue.pollFirst();
                if (dropped != null) {
                    recordDrop();
                }
                if (!queue.offerLast(info)) {
                    recordDrop();
                }
            } else {
                recordDrop();
            }
        }

        private void run() {
            List<TaskExecuteInfo> buffer = new ArrayList<>(MAX_BATCH_EVENTS);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TaskExecuteInfo first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }

                    buffer.clear();
                    buffer.add(first);
                    queue.drainTo(buffer, MAX_BATCH_EVENTS - 1);

                    sendBatch(buffer);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warn("[TaskLog] 批量发送异常: " + e.getMessage(), e);
                }
            }
        }

        private void sendBatch(List<TaskExecuteInfo> events) {
            TaskExecuteInfo groupSeed = null;
            StringBuilder lines = new StringBuilder();
            String lastResult = null;
            int lineCount = 0;
            int byteCount = 0;

            for (TaskExecuteInfo event : events) {
                if (event == null) {
                    continue;
                }

                if (isControl(event)) {
                    if (groupSeed != null) {
                        sendGrouped(groupSeed, lines.toString(), lastResult);
                        groupSeed = null;
                        lines.setLength(0);
                        lastResult = null;
                        lineCount = 0;
                        byteCount = 0;
                    }
                    sendSingle(event);
                    continue;
                }

                String line = event.getOutLine();
                if (line == null) {
                    continue;
                }

                boolean sameGroup = groupSeed != null && sameGroup(groupSeed, event);
                int extraBytes = line.length() + (lineCount == 0 ? 0 : 1);
                boolean exceedsLimit = lineCount >= MAX_BATCH_LINES
                        || (byteCount + extraBytes) > MAX_BATCH_BYTES;

                if (!sameGroup || exceedsLimit) {
                    if (groupSeed != null) {
                        sendGrouped(groupSeed, lines.toString(), lastResult);
                    }
                    groupSeed = event;
                    lines.setLength(0);
                    lastResult = null;
                    lineCount = 0;
                    byteCount = 0;
                }

                if (lineCount > 0) {
                    lines.append('\n');
                }
                lines.append(line);
                lineCount++;
                byteCount += extraBytes;
                lastResult = event.getResult();
            }

            if (groupSeed != null) {
                sendGrouped(groupSeed, lines.toString(), lastResult);
            }
        }

        private void sendGrouped(TaskExecuteInfo seed, String lines, String lastResult) {
            TaskExecuteInfo batch = new TaskExecuteInfo();
            batch.setTaskId(seed.getTaskId());
            batch.setExecuteId(seed.getExecuteId());
            batch.setIpAddress(seed.getIpAddress());
            batch.setOutLine(lines);
            batch.setResult(lastResult != null ? lastResult : seed.getResult());
            batch.setFinished(false);
            batch.setSuccess(seed.getSuccess());
            batch.setFirst(false);
            batch.setCanceled(seed.getCanceled());
            batch.setDispatchToken(seed.getDispatchToken());

            sendSingle(batch);
        }

        private void sendSingle(TaskExecuteInfo info) {
            try {
                SerializedSendServer
                        .sendOneway(TaskRpcService.class, "outputExecutionProcess", info);
            } catch (Exception e) {
                long now = System.currentTimeMillis();
                long last = lastSendFailLogAtMs.get();
                if (now - last >= SEND_FAIL_LOG_INTERVAL_MS
                        && lastSendFailLogAtMs.compareAndSet(last, now)) {
                    logger.warn("[TaskLog] 发送失败: " + e.getMessage());
                    AgentEventLogger.error("task_log_send_failed", "task log send failed", e);
                }
            }
        }

        private void recordDrop() {
            long dropped = droppedCount.incrementAndGet();
            long now = System.currentTimeMillis();
            long last = lastDropLogAtMs.get();
            if (now - last >= DROP_LOG_INTERVAL_MS
                    && lastDropLogAtMs.compareAndSet(last, now)) {
                logger.warn("[TaskLog] 日志被丢弃: totalDropped=" + dropped
                        + ", queueCapacity=" + QUEUE_CAPACITY);
                AgentEventLogger.event(
                        "task_log_dropped",
                        "WARN",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "task log dropped",
                        "totalDropped=" + dropped + ", queueCapacity=" + QUEUE_CAPACITY,
                        null,
                        null
                );
            }
        }

        private boolean isControl(TaskExecuteInfo info) {
            return Boolean.TRUE.equals(info.getFirst()) || Boolean.TRUE.equals(info.getFinished());
        }

        private boolean sameGroup(TaskExecuteInfo a, TaskExecuteInfo b) {
            return Objects.equals(a.getTaskId(), b.getTaskId())
                    && Objects.equals(a.getExecuteId(), b.getExecuteId())
                    && Objects.equals(a.getIpAddress(), b.getIpAddress())
                    && Objects.equals(a.getSuccess(), b.getSuccess());
        }
    }



}
