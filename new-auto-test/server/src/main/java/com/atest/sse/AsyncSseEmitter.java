package com.atest.sse;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SseEmitter wrapper that never writes on the caller thread.
 *
 * <p>Producers (agent frames, scheduler) only enqueue; a shared pool drains each subscriber in
 * order. A slow client that fills its queue is completed instead of stalling the producer, the UI
 * then reconnects and resumes from the last sequence it received.
 */
public class AsyncSseEmitter {

    private static final Logger log = LoggerFactory.getLogger(AsyncSseEmitter.class);

    private final SseEmitter emitter;
    private final Executor executor;
    private final int capacity;
    private final Queue<Message> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private record Message(String event, Object data, boolean comment) {
    }

    public AsyncSseEmitter(SseEmitter emitter, Executor executor, int capacity) {
        this.emitter = emitter;
        this.executor = executor;
        this.capacity = Math.max(16, capacity);
        this.emitter.onCompletion(() -> closed.set(true));
        this.emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        this.emitter.onError(t -> closed.set(true));
    }

    public SseEmitter emitter() {
        return emitter;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void send(String event, Object data) {
        enqueue(new Message(event, data, false));
    }

    public void comment(String text) {
        enqueue(new Message(null, text, true));
    }

    private void enqueue(Message message) {
        if (closed.get()) {
            return;
        }
        if (queued.get() >= capacity) {
            log.warn("sse subscriber too slow, dropping connection");
            closeQuietly();
            return;
        }
        queue.add(message);
        queued.incrementAndGet();
        schedule();
    }

    private void schedule() {
        if (queue.isEmpty() || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::drain);
        } catch (RuntimeException e) {
            draining.set(false);
            closeQuietly();
        }
    }

    private void drain() {
        try {
            Message message;
            while ((message = queue.poll()) != null) {
                queued.decrementAndGet();
                if (closed.get()) {
                    continue;
                }
                try {
                    if (message.comment()) {
                        emitter.send(SseEmitter.event().comment(String.valueOf(message.data())));
                    } else {
                        emitter.send(SseEmitter.event().name(message.event()).data(message.data()));
                    }
                } catch (IOException | IllegalStateException e) {
                    // client went away, Spring already failed the async request for us
                    abandon();
                } catch (Exception e) {
                    log.debug("sse send failed", e);
                    abandon();
                }
            }
        } finally {
            draining.set(false);
            if (!queue.isEmpty()) {
                schedule();
            }
        }
    }

    public void complete() {
        if (closed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // client already gone
            }
        }
    }

    /** Drop a dead stream without touching the emitter again. */
    private void abandon() {
        closed.set(true);
        queue.clear();
        queued.set(0);
    }

    private void closeQuietly() {
        complete();
        queue.clear();
        queued.set(0);
    }
}
