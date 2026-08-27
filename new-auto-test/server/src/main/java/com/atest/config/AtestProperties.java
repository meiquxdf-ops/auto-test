package com.atest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "atest")
public class AtestProperties {

    private final Agent agent = new Agent();
    private final Concurrency concurrency = new Concurrency();
    private final Logs logs = new Logs();
    private final Dispatch dispatch = new Dispatch();
    private final Sse sse = new Sse();

    @Getter
    @Setter
    public static class Agent {
        private int port = 9800;
        private String bindAddress = "0.0.0.0";
        private int maxFrameBytes = 1024 * 1024;
        private long dupSessionPingTimeoutMs = 5000;
        private int idleTimeoutSec = 90;
        private long requestTimeoutMs = 10000;
    }

    @Getter
    @Setter
    public static class Concurrency {
        private int defaultValue = 1;
        private int maxValue = 4;
    }

    @Getter
    @Setter
    public static class Logs {
        private long maxBytesPerExecution = 5L * 1024 * 1024;
        private int trimBatchSize = 500;
        private int maxPageSize = 5000;
    }

    @Getter
    @Setter
    public static class Dispatch {
        private long intervalMs = 1000;
        private int leaseSec = 45;
        private long reconcileIntervalMs = 5000;
        private int timeoutGraceSec = 15;
        private int disconnectedTimeoutSec = 3600;
        private int defaultTimeoutSec = 3600;
    }

    @Getter
    @Setter
    public static class Sse {
        private int queueCapacity = 2000;
        private long heartbeatMs = 15000;
        private long emitterTimeoutMs = 0;
    }
}
