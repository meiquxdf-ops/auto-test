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
    private final Attachments attachments = new Attachments();
    private final Http http = new Http();

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

    /**
     * 附件：脚本把测试机上的产物文件 POST 回 Server，存 Server 本地磁盘。
     * 上传的落盘 + 建档在专用有界线程池里做，绝不占着 Tomcat 请求线程等磁盘；
     * 超出准入水位（maxConcurrent + queueCapacity）的上传直接 429，突发不压垮 HTTP 池。
     */
    @Getter
    @Setter
    public static class Attachments {
        private String dir = "./data/attachments";
        /** 单文件硬上限（默认 32MB），与 spring.servlet.multipart.max-file-size 双保险 */
        private long maxBytes = 32L * 1024 * 1024;
        /** 同时在落盘的上传数上限（专用线程池大小） */
        private int maxConcurrent = 8;
        /** 落盘线程池前的等待队列长度，排满即 429 */
        private int queueCapacity = 8;
    }

    /** Agent 上脚本回连 Server HTTP 面用的地址，随任务注入为 ATEST_HTTP_BASE。 */
    @Getter
    @Setter
    public static class Http {
        private String publicBase = "http://127.0.0.1:8080";
    }

    /** 任务终态回调（开放 API）：POST callbackUrl，2xx 算成功，失败按退避重试后置 failed。 */
    @Getter
    @Setter
    public static class Callback {
        /**
         * SSRF 白名单：主机名 / IP / CIDR（如 cb.chaos.internal、10.9.0.0/16）。
         * 空列表 = 允许一般公网 http(s)，但一律拒绝 loopback / RFC1918 内网 /
         * link-local（含 169.254.169.254 元数据）等受保护地址；
         * 非空 = 只允许名单内的主机（显式列出的内网主机也放行）。
         */
        private java.util.List<String> allowedHosts = new java.util.ArrayList<>();
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
