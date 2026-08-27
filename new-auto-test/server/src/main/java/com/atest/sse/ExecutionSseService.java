package com.atest.sse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.atest.config.AtestProperties;
import com.atest.web.dto.LogLineView;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /api/sse/exec/{id}?from=: replay from a sequence, then live tail.
 *
 * <p>A subscription is registered before its backlog is read, live lines that arrive meanwhile are
 * buffered and replayed after the backlog, and every line is filtered by sequence so the client
 * never sees a duplicate or a gap.
 */
@Slf4j
@Service
public class ExecutionSseService {

    public static class Subscription {
        private final String executeId;
        private final AsyncSseEmitter emitter;
        private final List<LogLineView> buffered = new ArrayList<>();
        private int lastSeq;
        private boolean ready;

        Subscription(String executeId, AsyncSseEmitter emitter, int fromSeq) {
            this.executeId = executeId;
            this.emitter = emitter;
            this.lastSeq = fromSeq - 1;
        }

        public SseEmitter emitter() {
            return emitter.emitter();
        }

        synchronized void offer(LogLineView line) {
            if (!ready) {
                if (buffered.size() < 10000) {
                    buffered.add(line);
                }
                return;
            }
            sendLine(line);
        }

        public synchronized void flushBacklog(List<LogLineView> backlog) {
            for (LogLineView line : backlog) {
                sendLine(line);
            }
            for (LogLineView line : buffered) {
                sendLine(line);
            }
            buffered.clear();
            ready = true;
        }

        private void sendLine(LogLineView line) {
            if (line.seq() <= lastSeq) {
                return;
            }
            lastSeq = line.seq();
            emitter.send("log", line);
        }

        public void sendEvent(String event, Object payload) {
            emitter.send(event, payload);
        }

        boolean isClosed() {
            return emitter.isClosed();
        }
    }

    private final AtestProperties props;
    private final ExecutorService sseExecutor;
    private final ScheduledExecutorService heartbeatScheduler;
    private final Map<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();

    public ExecutionSseService(AtestProperties props,
                               @Qualifier("sseExecutor") ExecutorService sseExecutor,
                               @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.props = props;
        this.sseExecutor = sseExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @PostConstruct
    void startHeartbeat() {
        long period = Math.max(1000, props.getSse().getHeartbeatMs());
        heartbeatScheduler.scheduleAtFixedRate(() -> subscriptions.forEach((executeId, subs) -> {
            subs.removeIf(Subscription::isClosed);
            subs.forEach(s -> s.emitter.comment("hb"));
            if (subs.isEmpty()) {
                subscriptions.remove(executeId, subs);
            }
        }), period, period, TimeUnit.MILLISECONDS);
    }

    public Subscription subscribe(String executeId, int fromSeq) {
        SseEmitter raw = new SseEmitter(props.getSse().getEmitterTimeoutMs() <= 0
                ? Long.MAX_VALUE : props.getSse().getEmitterTimeoutMs());
        AsyncSseEmitter async = new AsyncSseEmitter(raw, sseExecutor, props.getSse().getQueueCapacity());
        Subscription sub = new Subscription(executeId, async, Math.max(0, fromSeq));
        Set<Subscription> set = subscriptions.computeIfAbsent(executeId, k -> ConcurrentHashMap.newKeySet());
        set.add(sub);
        Runnable cleanup = () -> {
            set.remove(sub);
            if (set.isEmpty()) {
                subscriptions.remove(executeId, set);
            }
        };
        raw.onCompletion(cleanup);
        raw.onTimeout(cleanup);
        raw.onError(t -> cleanup.run());
        return sub;
    }

    public void publishLines(String executeId, List<LogLineView> lines) {
        Set<Subscription> subs = subscriptions.get(executeId);
        if (subs == null || subs.isEmpty() || lines.isEmpty()) {
            return;
        }
        for (Subscription sub : subs) {
            if (sub.isClosed()) {
                subs.remove(sub);
                continue;
            }
            for (LogLineView line : lines) {
                sub.offer(line);
            }
        }
    }

    public void publishTruncated(String executeId, int minSeq, long logBytes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("truncated", true);
        payload.put("minSeq", minSeq);
        payload.put("logBytes", logBytes);
        payload.put("maxBytes", props.getLogs().getMaxBytesPerExecution());
        broadcast(executeId, "truncated", payload);
    }

    public void publishStatus(String executeId, Object payload) {
        broadcast(executeId, "status", payload);
    }

    public void publishEnd(String executeId, Object payload) {
        broadcast(executeId, "end", payload);
        Set<Subscription> subs = subscriptions.get(executeId);
        if (subs == null) {
            return;
        }
        // give the queued end event a moment to flush before tearing the stream down
        heartbeatScheduler.schedule(() -> subs.forEach(s -> s.emitter.complete()), 1500, TimeUnit.MILLISECONDS);
    }

    private void broadcast(String executeId, String event, Object payload) {
        Set<Subscription> subs = subscriptions.get(executeId);
        if (subs == null || subs.isEmpty()) {
            return;
        }
        for (Subscription sub : subs) {
            if (sub.isClosed()) {
                subs.remove(sub);
            } else {
                sub.sendEvent(event, payload);
            }
        }
    }
}
