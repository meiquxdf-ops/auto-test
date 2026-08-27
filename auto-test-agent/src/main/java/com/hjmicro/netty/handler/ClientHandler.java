package com.hjmicro.netty.handler;

import com.hjmicro.RpcQueue;
import com.hjmicro.ServiceInterface;
import com.hjmicro.domain.MethodInvokeDefinition;
import com.hjmicro.domain.dto.HeartbeatMessage;
import com.hjmicro.domain.dto.PingMessage;
import com.hjmicro.domain.dto.PongMessage;
import com.hjmicro.domain.dto.RpcRequest;
import com.hjmicro.domain.dto.RpcResult;
import com.hjmicro.netty.SerializedSendServer;
import com.hjmicro.server.service.HeartbeatService;
import com.hjmicro.service.AgentEventLogger;
import com.hjmicro.service.TaskHandlerServiceImpl;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.log4j.Logger;
import org.reflections.Reflections;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@ChannelHandler.Sharable
public class ClientHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger logger = Logger.getLogger(ClientHandler.class);

    private static Map<String, MethodInvokeDefinition> methodInfoMap = new HashMap<>();

    private static ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 10, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(100));

    // 心跳相关
    private static ScheduledExecutorService heartbeatScheduler;
    private static ScheduledFuture<?> heartbeatFuture;
    private static volatile ChannelHandlerContext currentCtx;
    private static volatile boolean heartbeatInitialized = false;

    private static final AtomicLong heartbeatOkCount = new AtomicLong(0);
    private static final AtomicLong heartbeatFailCount = new AtomicLong(0);
    private static final AtomicLong lastHeartbeatLogAtMs = new AtomicLong(0);
    private static volatile String lastExecuteStatus = null;

    /**
     * 启动心跳定时任务
     */
    private static synchronized void startHeartbeat() {
        if (heartbeatInitialized) {
            // 已经初始化过，只需更新 ctx
            logger.debug("[Heartbeat] 重连成功，心跳定时器继续使用现有实例");
            return;
        }
        heartbeatInitialized = true;

        SystemInfo si = new SystemInfo();
        HardwareAbstractionLayer hal = si.getHardware();
        OperatingSystem os = si.getOperatingSystem();
        CentralProcessor processor = hal.getProcessor();
        GlobalMemory memory = hal.getMemory();

        String hostAddress;
        try {
            InetAddress ip = InetAddress.getLocalHost();
            hostAddress = ip.getHostAddress();
            if (hostAddress.contains("127.0.0.1") || hostAddress.contains("192.168")) {
                hostAddress = getActualAddress();
            }
        } catch (Exception e) {
            hostAddress = "unknown";
            logger.warn("[Heartbeat] 获取本机IP失败: " + e.getMessage());
        }

        HeartbeatMessage message = new HeartbeatMessage();
        message.setOperatingSystem(os.toString());
        message.setProcessor(processor.toString());
        message.setMemory(memory.toString());
        message.setCpuUsage(processor.getSystemCpuLoad(1000) * 100);
        message.setTotalMemory(memory.getTotal());
        message.setAvailableMemory(memory.getAvailable());
        message.setIpAddress(hostAddress);

        if (isDockerContainer()) {
            message.setDockerContainerId(getDockerContainerIdFromEnv());
            message.setDockerContainerName(getDockerContainerName());
            message.setDockerContainer(true);
        } else {
            message.setDockerContainer(false);
        }

        // 读取配置文件
        Properties properties = new Properties();
        try {
            properties.load(Files.newInputStream(Paths.get("/etc/hjmicro/config.properties")));
        } catch (IOException e) {
            logger.debug("未找到配置文件: " + e.getMessage());
        }
        if (properties.get("LocalHostIP") != null) {
            message.setIpAddress((String) properties.get("LocalHostIP"));
        }

        // 创建定时器
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-scheduler");
            t.setDaemon(true);
            return t;
        });

        final String finalHostAddress = message.getIpAddress();
        AgentEventLogger.setMachineTag(finalHostAddress);
        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
            // 检查 channel 是否可用
            ChannelHandlerContext ctx = currentCtx;
            if (ctx == null || !ctx.channel().isActive()) {
                // 不输出日志，避免刷屏
                return;
            }

            TaskHandlerServiceImpl.AgentStateSnapshot stateSnapshot =
                    TaskHandlerServiceImpl.getAgentStateSnapshot();
            Long runningExecuteId = stateSnapshot.getRunningExecuteId();
            String currentStatus = runningExecuteId == null ? "idle" : "running";
            message.setExecuteStatus(currentStatus);
            message.setAgentSessionId(stateSnapshot.getAgentSessionId());
            message.setStateVersion(stateSnapshot.getStateVersion());
            message.setRunningExecuteId(runningExecuteId);
            message.setDispatchToken(stateSnapshot.getRunningDispatchToken());
            message.setTag(properties.getProperty("tag"));
            message.setOperatingSystem((String) properties.getOrDefault("Operating.System", message.getOperatingSystem()));

            try {
                Path path = Paths.get("/");
                FileStore store = Files.getFileStore(path);
                long total = store.getTotalSpace() / 1024 / 1024 / 1024;
                long used = (store.getTotalSpace() - store.getUnallocatedSpace()) / 1024 / 1024 / 1024;
                double usage = (double) used / total * 100;
                message.setDiskUsage(String.format("%.2f", usage) + "%");
            } catch (IOException e) {
                // ignore
            }

            message.setCpuUsage(processor.getSystemCpuLoad(1000) * 100);
            message.setTotalMemory(memory.getTotal());
            message.setSubmitTime(System.currentTimeMillis());
            message.setAvailableMemory(memory.getAvailable());

            try {
                SerializedSendServer.sendOneway(HeartbeatService.class, "heartbeat", message);
                heartbeatOkCount.incrementAndGet();

                // 状态变化时立即输出日志
                boolean statusChanged = lastExecuteStatus != null && !lastExecuteStatus.equals(currentStatus);
                if (statusChanged) {
                    logger.info("[Heartbeat] 状态变化: " + lastExecuteStatus + " -> " + currentStatus
                            + ", ip=" + finalHostAddress
                            + ", tag=" + message.getTag()
                            + ", activeTaskCount=" + TaskHandlerServiceImpl.getActiveCount());
                    AgentEventLogger.event(
                            "heartbeat_status_changed",
                            "INFO",
                            null,
                            runningExecuteId,
                            stateSnapshot.getRunningDispatchToken(),
                            null,
                            null,
                            "heartbeat status changed: " + lastExecuteStatus + " -> " + currentStatus,
                            "ip=" + finalHostAddress + ", tag=" + message.getTag()
                                    + ", activeTaskCount=" + TaskHandlerServiceImpl.getActiveCount(),
                            null,
                            null
                    );
                }
                lastExecuteStatus = currentStatus;

                // 每分钟输出一次汇总日志
                long now = System.currentTimeMillis();
                long lastLog = lastHeartbeatLogAtMs.get();
                // 心跳汇总日志改为5分钟输出一次
                if (now - lastLog >= 300_000 && lastHeartbeatLogAtMs.compareAndSet(lastLog, now)) {
                    logger.info("[Heartbeat] 汇总: status=" + currentStatus
                            + ", ip=" + finalHostAddress
                            + ", tag=" + message.getTag()
                            + ", cpu=" + String.format("%.1f", message.getCpuUsage()) + "%"
                            + ", mem=" + (message.getAvailableMemory() / 1024 / 1024) + "MB/" + (message.getTotalMemory() / 1024 / 1024) + "MB"
                            + ", disk=" + message.getDiskUsage()
                            + ", ok=" + heartbeatOkCount.get()
                            + ", fail=" + heartbeatFailCount.get());
                }
            } catch (Exception e) {
                heartbeatFailCount.incrementAndGet();
                // 连接断开时不输出异常堆栈，避免刷屏
                if (currentCtx != null && currentCtx.channel().isActive()) {
                    logger.warn("[Heartbeat] 发送失败: ip=" + finalHostAddress + ", error=" + e.getMessage());
                    AgentEventLogger.error("heartbeat_send_failed",
                            "heartbeat send failed, ip=" + finalHostAddress, e);
                }
            }
        }, 0, 5, TimeUnit.SECONDS);

        logger.info("[Heartbeat] 心跳定时器已启动, ip=" + finalHostAddress);
        AgentEventLogger.info("heartbeat_started", "heartbeat started, ip=" + finalHostAddress);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof PingMessage ping) {
            PongMessage pong = new PongMessage();
            pong.setRequestId(ping.getRequestId());
            pong.setTimestamp(System.currentTimeMillis());
            ctx.writeAndFlush(pong);
            if (logger.isDebugEnabled()) {
                logger.debug("Ping received -> Pong sent, requestId=" + ping.getRequestId());
            }
            return;
        }
        if (msg instanceof RpcRequest request) {
            executor.execute(() -> {
                MethodInvokeDefinition methodInvokeDefinition =
                        methodInfoMap.get(request.sign);
                Object result = null;
                try {
                    result =
                            methodInvokeDefinition.getMethod().invoke(methodInvokeDefinition.getInstance(), request.getArgs());
                } catch (IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
                RpcResult rpcResult = new RpcResult();
                rpcResult.setRequestId(request.getRequestId());
                rpcResult.setResult(result);
                SerializedSendServer.put(rpcResult);
                if (logger.isDebugEnabled()) {
                    logger.debug("[RPC] Received: sign=" + request.getSign());
                }
            });
        }

        if (msg instanceof RpcResult result) {
            boolean signaled = RpcQueue.signal(result);
            if (!signaled && logger.isDebugEnabled()) {
                logger.debug("Orphan RpcResult ignored, requestId=" + result.getRequestId());
            }
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("[Connection] Server 连接成功!");
        AgentEventLogger.info("server_connected", "server connected, remote=" + ctx.channel().remoteAddress());

        // 更新当前 ctx
        currentCtx = ctx;
        SerializedSendServer.setCtx(ctx);

        // 启动心跳（如果已启动则复用现有定时器）
        startHeartbeat();

        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.warn("[Connection] Server 连接断开，等待重连...");
        AgentEventLogger.warn("server_disconnected", "server disconnected, remote=" + ctx.channel().remoteAddress());

        // 清空当前 ctx，心跳定时器会检测到并跳过发送
        currentCtx = null;
        SerializedSendServer.setCtx(null);

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("[Connection] 异常: " + cause.getMessage());
        AgentEventLogger.error("server_connection_exception", "server connection exception", cause);
        super.exceptionCaught(ctx, cause);
    }

    public void initClientHandler() throws InstantiationException,
            IllegalAccessException {
        Reflections reflections = new Reflections("com.hjmicro");
        Set<Class<? extends ServiceInterface>> subTypesOf =
                reflections.getSubTypesOf(ServiceInterface.class);
        for (Class<? extends ServiceInterface> aClass : subTypesOf) {

            HashMap<String, Class> methodInterfaceMap = new HashMap<>();
            Class<?>[] interfaces = aClass.getInterfaces();
            for (Class<?> interfaceClass : interfaces) {
                for (Method interfaceMethod : interfaceClass.getMethods()) {
                    String name = interfaceMethod.getName();
                    methodInterfaceMap.put(name, interfaceClass);
                }
            }

            if (!aClass.isInterface()) {
                ServiceInterface serviceInterface = aClass.newInstance();
                Method[] methods = aClass.getDeclaredMethods();
                for (Method method : methods) {
                    if (method.getDeclaringClass() != Object.class) {
                        MethodInvokeDefinition methodInvokeDefinition =
                                new MethodInvokeDefinition();
                        methodInvokeDefinition.setInstance(serviceInterface);
                        methodInvokeDefinition.setMethodName(method.getName());
                        methodInvokeDefinition.setReturnType(method.getReturnType());
                        methodInvokeDefinition.setParameterTypes(method.getParameterTypes());
                        methodInvokeDefinition.setMethod(method);
                        Class<?> interfaceClass =
                                methodInterfaceMap.get(method.getName());
                        if (interfaceClass == null) {
                            continue;
                        }
                        methodInfoMap.put(interfaceClass.getName() + "#" + method.getName() + "#" + Arrays.toString(method.getParameterTypes()), methodInvokeDefinition);
                    }
                }
            }
        }
    }

    public static String getActualAddress() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        String fallbackAddress = null;

        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();

            if (networkInterface.isLoopback() || !networkInterface.isUp() || networkInterface.isVirtual()) {
                continue;
            }

            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();

                if (addr.isSiteLocalAddress() && !addr.isLoopbackAddress() && !addr.isAnyLocalAddress()) {
                    String hostAddress = addr.getHostAddress();

                    if (networkInterface.getName().equals("eth0") && Pattern.matches("^10\\..*", hostAddress)) {
                        return hostAddress;
                    }

                    if (Pattern.matches("^10\\..*", hostAddress) && fallbackAddress == null) {
                        fallbackAddress = hostAddress;
                    }
                }
            }
        }

        return fallbackAddress;
    }

    public static boolean isDockerContainer() {
        try {
            List<String> lines = Files.readAllLines(Paths.get("/proc/1/cgroup"));
            for (String line : lines) {
                if (line.contains("docker") || line.contains("/docker-ce/")) {
                    return true;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return false;
    }

    public static String getDockerContainerIdFromEnv() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isEmpty()) {
            return hostname;
        } else {
            return null;
        }
    }

    public static String getDockerContainerName() {
        String hostname = System.getenv("CONTAINERNAME");
        if (hostname != null && !hostname.isEmpty()) {
            return hostname;
        } else {
            return null;
        }
    }
}
