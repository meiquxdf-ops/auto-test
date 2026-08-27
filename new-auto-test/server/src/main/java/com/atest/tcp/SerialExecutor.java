package com.atest.tcp;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs tasks on a shared pool while preserving submission order per connection. */
public final class SerialExecutor implements Executor {

    private static final Logger log = LoggerFactory.getLogger(SerialExecutor.class);

    private final Executor delegate;
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    public SerialExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        queue.add(command);
        schedule();
    }

    private void schedule() {
        if (queue.isEmpty() || !draining.compareAndSet(false, true)) {
            return;
        }
        delegate.execute(this::drain);
    }

    private void drain() {
        try {
            Runnable task;
            while ((task = queue.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    log.error("serial task failed", t);
                }
            }
        } finally {
            draining.set(false);
            if (!queue.isEmpty()) {
                schedule();
            }
        }
    }
}
