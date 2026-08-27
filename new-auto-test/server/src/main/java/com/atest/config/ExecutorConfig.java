package com.atest.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ExecutorConfig {

    /** Blocking (DB touching) work triggered by agent frames; never runs on a Netty event loop. */
    @Bean(name = "agentWorkExecutor", destroyMethod = "shutdown")
    public ExecutorService agentWorkExecutor() {
        return new ThreadPoolExecutor(8, 32, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(20000), namedFactory("agent-work-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** Outbound SSE writes, kept off request/agent threads. */
    @Bean(name = "sseExecutor", destroyMethod = "shutdown")
    public ExecutorService sseExecutor() {
        return new ThreadPoolExecutor(4, 16, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10000), namedFactory("sse-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "sseHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService sseHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(namedFactory("sse-hb-"));
    }

    /** Dispatch / reconcile timers. */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("atest-sched-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
