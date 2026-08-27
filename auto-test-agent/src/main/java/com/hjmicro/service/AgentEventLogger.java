package com.hjmicro.service;

import com.hjmicro.domain.dto.AgentEventDTO;
import com.hjmicro.netty.SerializedSendServer;
import com.hjmicro.server.service.AgentEventService;
import org.apache.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class AgentEventLogger {

    private static final Logger logger = Logger.getLogger(AgentEventLogger.class);
    private static final Path EVENT_LOG_PATH = Paths.get("logs", "agent-events.log");
    private static final Object FILE_LOCK = new Object();
    private static final ThreadPoolExecutor REPORT_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(200),
            r -> {
                Thread thread = new Thread(r, "agent-event-reporter");
                thread.setDaemon(true);
                return thread;
            }
    );
    private static final long REPORT_FAILURE_LOG_INTERVAL_MS = 10_000L;
    private static final AtomicLong LAST_REPORT_FAILURE_LOG_AT_MS = new AtomicLong(0);

    private static volatile String machineTag;
    private static volatile String server;

    private AgentEventLogger() {
    }

    public static void setMachineTag(String machineTag) {
        if (machineTag != null && !machineTag.isBlank()) {
            AgentEventLogger.machineTag = machineTag;
        }
    }

    public static void setServer(String host, int port) {
        if (host != null) {
            AgentEventLogger.server = host + ":" + port;
        }
    }

    public static void info(String eventType, String message) {
        event(eventType, "INFO", null, null, null, null, null, message, null, null, null);
    }

    public static void warn(String eventType, String message) {
        event(eventType, "WARN", null, null, null, null, null, message, null, null, null);
    }

    public static void error(String eventType, String message, Throwable error) {
        event(eventType, "ERROR", null, null, null, null, null, message, null, null, error);
    }

    public static void event(String eventType, String level, Long taskId, Long executeId,
                             String dispatchToken, String requestId, String command,
                             String message, String detail, Long durationMs, Throwable error) {
        AgentEventDTO event = new AgentEventDTO();
        event.setEventId(UUID.randomUUID().toString());
        event.setTime(System.currentTimeMillis());
        event.setEventType(eventType);
        event.setLevel(level);
        event.setMachineTag(machineTag);
        event.setAgentSessionId(TaskHandlerServiceImpl.getAgentSessionId());
        event.setStateVersion(TaskHandlerServiceImpl.getStateVersion());
        event.setTaskId(taskId);
        event.setExecuteId(executeId);
        event.setDispatchToken(dispatchToken);
        event.setRequestId(requestId);
        event.setCommand(command);
        event.setMessage(message);
        event.setDetail(detail);
        event.setDurationMs(durationMs);
        event.setServer(server);
        event.setThread(Thread.currentThread().getName());
        if (error != null) {
            event.setErrorType(error.getClass().getName());
            event.setErrorMessage(error.getMessage());
        }

        writeLocal(event);
        try {
            REPORT_EXECUTOR.execute(() -> reportToServer(event));
        } catch (Exception e) {
            writeReportFailure("event report queue rejected: " + e.getMessage(), e);
        }
    }

    private static void reportToServer(AgentEventDTO event) {
        try {
            SerializedSendServer.sendOneway(AgentEventService.class, "reportEvent", event);
        } catch (Exception e) {
            writeReportFailure("event report failed: " + e.getMessage(), e);
        }
    }

    private static void writeReportFailure(String message, Exception error) {
        long now = System.currentTimeMillis();
        long last = LAST_REPORT_FAILURE_LOG_AT_MS.get();
        if (now - last < REPORT_FAILURE_LOG_INTERVAL_MS
                || !LAST_REPORT_FAILURE_LOG_AT_MS.compareAndSet(last, now)) {
            return;
        }
        AgentEventDTO event = new AgentEventDTO();
        event.setEventId(UUID.randomUUID().toString());
        event.setTime(now);
        event.setEventType("agent_event_report_failed");
        event.setLevel("WARN");
        event.setMachineTag(machineTag);
        event.setAgentSessionId(TaskHandlerServiceImpl.getAgentSessionId());
        event.setStateVersion(TaskHandlerServiceImpl.getStateVersion());
        event.setMessage(message);
        event.setServer(server);
        event.setThread(Thread.currentThread().getName());
        event.setErrorType(error.getClass().getName());
        event.setErrorMessage(error.getMessage());
        writeLocal(event);
    }

    private static void writeLocal(AgentEventDTO event) {
        synchronized (FILE_LOCK) {
            try {
                Files.createDirectories(EVENT_LOG_PATH.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(
                        EVENT_LOG_PATH,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                )) {
                    writer.write(toJson(event));
                    writer.newLine();
                }
            } catch (IOException e) {
                logger.warn("[AgentEvent] write local event failed: " + e.getMessage());
            }
        }
    }

    private static String toJson(AgentEventDTO event) {
        StringBuilder builder = new StringBuilder(512);
        builder.append('{');
        append(builder, "eventId", event.getEventId());
        append(builder, "time", event.getTime());
        append(builder, "eventType", event.getEventType());
        append(builder, "level", event.getLevel());
        append(builder, "machineTag", event.getMachineTag());
        append(builder, "agentSessionId", event.getAgentSessionId());
        append(builder, "stateVersion", event.getStateVersion());
        append(builder, "taskId", event.getTaskId());
        append(builder, "executeId", event.getExecuteId());
        append(builder, "dispatchToken", event.getDispatchToken());
        append(builder, "requestId", event.getRequestId());
        append(builder, "command", event.getCommand());
        append(builder, "message", event.getMessage());
        append(builder, "detail", event.getDetail());
        append(builder, "durationMs", event.getDurationMs());
        append(builder, "errorType", event.getErrorType());
        append(builder, "errorMessage", event.getErrorMessage());
        append(builder, "server", event.getServer());
        append(builder, "thread", event.getThread());
        if (builder.charAt(builder.length() - 1) == ',') {
            builder.deleteCharAt(builder.length() - 1);
        }
        builder.append('}');
        return builder.toString();
    }

    private static void append(StringBuilder builder, String key, String value) {
        if (value == null) {
            return;
        }
        builder.append('"').append(key).append("\":\"").append(escape(value)).append("\",");
    }

    private static void append(StringBuilder builder, String key, Long value) {
        if (value == null) {
            return;
        }
        builder.append('"').append(key).append("\":").append(value).append(',');
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }
}
