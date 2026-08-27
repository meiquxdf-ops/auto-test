package com.atest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "atest")
public class AtestProperties {

    private final Agent agent = new Agent();
    private final AgentDist agentDist = new AgentDist();
    private final SshInstall sshInstall = new SshInstall();
    private final Concurrency concurrency = new Concurrency();
    private final Logs logs = new Logs();
    private final Dispatch dispatch = new Dispatch();
    private final Sse sse = new Sse();
    private final Callback callback = new Callback();

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

    /** curl / SSH 在线安装的分发目录：linux/amd64 静态 atagent 二进制在打包/编排时拷进来。 */
    @Getter
    @Setter
    public static class AgentDist {
        private String dir = "./dist/agent";
    }

    /** 「SSH 代装」：Server 主动 SSH 到目标机执行 install.sh。 */
    @Getter
    @Setter
    public static class SshInstall {
        private long connectTimeoutMs = 10_000;
        private long authTimeoutMs = 10_000;
        /** install.sh 自己最多等 20s 注册，再叠加上传/systemd 时间，给足余量 */
        private long execTimeoutMs = 180_000;
        /** 返回给页面的输出只保留最后 N 行 */
        private int outputTailLines = 200;
        /** accept-new 语义的 known_hosts；首次连接记录指纹，之后指纹变了拒绝 */
        private String knownHostsFile = "./data/ssh-known-hosts";
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

    /** 任务终态回调（开放 API）：POST callbackUrl，2xx 算成功，失败按退避重试后置 failed。 */
    @Getter
    @Setter
    public static class Callback {
        /** total delivery attempts before the callback is marked failed: 1 initial + 5 retries */
        private int maxAttempts = 6;
        /** wait before attempt N+1 is backoffBaseMs << (N-1): 1s, 2s, 4s, 8s, 16s */
        private long backoffBaseMs = 1000;
        /** connect + response timeout of a single attempt */
        private long timeoutMs = 10_000;
        /** requeue sweep for callbacks that never got claimed (e.g. restart mid-flight) */
        private long sweepIntervalMs = 30_000;
    }
}
