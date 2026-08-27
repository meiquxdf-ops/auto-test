package com.hjmicro.service.impl.rpc;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Resource;

import com.hjmicro.fluent.entity.TaskEntity;
import com.hjmicro.fluent.wrapper.*;
import com.hjmicro.netty.ConnectionRegistry;
import com.hjmicro.netty.handler.RequestContext;
import com.hjmicro.service.DispatchStateRegistry;
import com.hjmicro.service.DispatchStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.hjmicro.domain.CommonConstant;
import com.hjmicro.domain.dto.HeartbeatMessage;
import com.hjmicro.fluent.entity.HeartbeatEntity;
import com.hjmicro.fluent.entity.MachineInfoEntity;
import com.hjmicro.fluent.entity.TaskExecutionEntity;
import com.hjmicro.fluent.mapper.HeartbeatMapper;
import com.hjmicro.fluent.mapper.MachineInfoMapper;
import com.hjmicro.fluent.mapper.TaskExecutionMapper;
import com.hjmicro.fluent.mapper.TaskMapper;
import com.hjmicro.server.service.HeartbeatService;
import com.hjmicro.service.TaskService;
import org.springframework.data.redis.core.StringRedisTemplate;

@Service
@Slf4j
public class HeartbeatServiceImpl implements HeartbeatService {

    private static final long HEARTBEAT_IDLE_TIMEOUT_MS = 20_000L;
    // running 状态超时时间：2分钟（原来是10分钟太长，任务执行中 Agent 也会发送心跳）
    private static final long HEARTBEAT_RUNNING_TIMEOUT_MS = 2 * 60_000L;

    @Resource
    private MachineInfoMapper machineInfoMapper;

    @Resource
    private HeartbeatMapper heartbeatMapper;

    @Resource
    private TaskMapper taskMapper;

    @Resource
    private TaskExecutionMapper taskExecutionMapper;

    @Resource
    private TaskService taskService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DispatchStateStore dispatchStateStore;

    private final Map<String, Long> machineTagTimeMap = new ConcurrentHashMap<>();

    private final Map<String, HeartbeatMessage> machineTagMessageMap = new ConcurrentHashMap<>();
    private static final Map<String, String> machineTagAndLinkHostPortAnd = new ConcurrentHashMap<>();
    private static final Map<String, String> machineAliasToMachineTag = new ConcurrentHashMap<>();

    @Override
    public boolean heartbeat(HeartbeatMessage message) {
        if (RequestContext.getRequestContext() == null) {
            log.warn("[Heartbeat] 收到心跳但无 RequestContext, agentIp={}", message.getIpAddress());
            return false;
        }
        InetSocketAddress socketAddress =
                (InetSocketAddress) RequestContext.getRequestContext().channel().remoteAddress();
        String linkIp = socketAddress.getAddress().getHostAddress();
        int linkPort = socketAddress.getPort();

        message.setLinkIp(linkIp);
        message.setLinkPort(linkPort);

        String machineTag = resolveMachineTag(message, linkIp);

        message.setMachineTag(machineTag);

        String linkHostPort = linkIp + ":" + linkPort;
        machineTagAndLinkHostPortAnd.put(machineTag, linkHostPort);

        long now = System.currentTimeMillis();

        // 检测机器状态变化
        boolean isNewMachine = !machineTagTimeMap.containsKey(machineTag);
        HeartbeatMessage prevMessage = machineTagMessageMap.get(machineTag);
        String prevStatus = prevMessage != null ? prevMessage.getExecuteStatus() : null;
        boolean statusChanged = prevStatus != null && !prevStatus.equals(message.getExecuteStatus());

        if (isNewMachine) {
            log.info("[Heartbeat] 新机器上线: machineTag={}, linkIp={}, agentIp={}, tag={}, isDocker={}, containerId={}",
                    machineTag, linkIp, message.getIpAddress(), message.getTag(),
                    message.getDockerContainer(), message.getDockerContainerId());
        } else if (statusChanged) {
            log.info("[Heartbeat] 状态变化: machineTag={}, {} -> {}, linkIp={}",
                    machineTag, prevStatus, message.getExecuteStatus(), linkIp);
        }

        machineTagTimeMap.put(machineTag, now);
        machineTagMessageMap.put(machineTag, message);
        registerAliases(message, machineTag, linkIp, linkHostPort, now);
        DispatchStateRegistry.AgentState agentState =
                dispatchStateStore.recordHeartbeat(machineTag, message);

        // debug 级别的详细心跳日志
        if (log.isDebugEnabled()) {
            log.debug("[Heartbeat] machineTag={}, status={}, linkIp={}, agentIp={}, cpu={}%, mem={}MB/{}MB, disk={}",
                    machineTag, message.getExecuteStatus(), linkIp, message.getIpAddress(),
                    String.format("%.1f", message.getCpuUsage()),
                    message.getAvailableMemory() / 1024 / 1024, message.getTotalMemory() / 1024 / 1024,
                    message.getDiskUsage());
        }

        //todo 空闲和下发到执行有并发情况

        if (CommonConstant.IDLE.equals(message.getExecuteStatus())) {
            if (handleIdleForActiveDispatch(machineTag, agentState)) {
                return Boolean.TRUE;
            }
            Long currentExecuteId = taskService.trySchedule(machineTag);
            if (currentExecuteId == null && message.getIpAddress() != null && !message.getIpAddress().isBlank()
                    && !Objects.equals(message.getIpAddress(), machineTag)) {
                taskService.trySchedule(message.getIpAddress());
            }
        }
        return Boolean.TRUE;
    }

    private boolean handleIdleForActiveDispatch(String machineTag, DispatchStateRegistry.AgentState agentState) {
        DispatchStateRegistry.DispatchState dispatchState = dispatchStateStore.getDispatch(machineTag);
        if (dispatchState == null) {
            return false;
        }
        if (Objects.equals(agentState.getRunningExecuteId(), dispatchState.getExecuteId())
                && Objects.equals(agentState.getDispatchToken(), dispatchState.getDispatchToken())) {
            return true;
        }
        boolean sameSession = Objects.equals(agentState.getAgentSessionId(), dispatchState.getBaseAgentSessionId());
        if (sameSession && agentState.getStateVersion() <= dispatchState.getBaseStateVersion()) {
            log.debug("[Heartbeat] 忽略旧 idle 心跳: machineTag={}, executeId={}",
                    machineTag, dispatchState.getExecuteId());
            return true;
        }

        TaskExecutionEntity execution = taskExecutionMapper.findById(dispatchState.getExecuteId());
        if (execution != null && CommonConstant.RUNNING.equals(execution.getExecuteStatus())) {
            log.debug("[Heartbeat] 忽略 running 任务期间的 idle 心跳: machineTag={}, executeId={}",
                    machineTag, execution.getId());
            return true;
        }
        if (execution != null && isActiveExecution(execution.getExecuteStatus())) {
            String savedLogs = rescueLogsFromRedis(execution.getId());
            Date endTime = new Date();
            TaskExecutionUpdate update = new TaskExecutionUpdate()
                    .where.id().eq(execution.getId())
                    .and.dispatchToken().eq(dispatchState.getDispatchToken())
                    .and.executeStatus().in(Arrays.asList(CommonConstant.DISPATCHING, CommonConstant.RUNNING))
                    .end()
                    .set.status().is(CommonConstant.EXCEPTION)
                    .executeStatus().is(CommonConstant.EXCEPTION)
                    .endTime().is(endTime)
                    .logs().is(savedLogs.isEmpty()
                            ? "Task execution failed, agent reported idle after dispatch"
                            : savedLogs + "\n\n[Agent reported idle after dispatch]")
                    .end();
            if (execution.getStartTime() != null) {
                update.set.executeTime().is((int)(endTime.getTime() - execution.getStartTime().getTime()));
            }
            int updated = taskExecutionMapper.updateBy(update);
            if (updated > 0) {
                taskService.updateMainTask(execution.getTaskId());
            } else {
                log.debug("[Heartbeat] idle 补偿未更新任务，execution 已非 active: machineTag={}, executeId={}",
                        machineTag, dispatchState.getExecuteId());
            }
        } else if (execution != null) {
            log.debug("[Heartbeat] idle 补偿跳过终态任务: machineTag={}, executeId={}, executeStatus={}, status={}",
                    machineTag, execution.getId(), execution.getExecuteStatus(), execution.getStatus());
        }
        dispatchStateStore.clearIfCurrent(
                machineTag,
                dispatchState.getTaskId(),
                dispatchState.getExecuteId(),
                dispatchState.getDispatchToken()
        );
        taskService.trySchedule(machineTag);
        return true;
    }

    private boolean isActiveExecution(String executeStatus) {
        return CommonConstant.DISPATCHING.equals(executeStatus)
                || CommonConstant.RUNNING.equals(executeStatus);
    }

    @Override
    public boolean isIdle(String ip) {
        HeartbeatMessage heartbeatMessage ;
        synchronized (this) { // 确保线程安全
            heartbeatMessage = machineTagMessageMap.get(ip);
        }
        if (heartbeatMessage == null) {
            return false;
        }
        return CommonConstant.IDLE.equals(heartbeatMessage.getExecuteStatus()); // 在20秒内的被认为是在线
    }

    @Override
    public boolean isOnline(String ip) {
        Long lastHeartbeat;
        synchronized (this) { // 确保线程安全
            lastHeartbeat = machineTagTimeMap.get(ip);
        }
        if (lastHeartbeat == null) {
            boolean connected = isConnectedByTagOrAlias(ip);
            if (connected && log.isDebugEnabled()) {
                log.debug("isOnline fallback to tcp connection, key={}", ip);
            }
            return connected;
        }
        HeartbeatMessage message = machineTagMessageMap.get(ip);
        long timeout = getOnlineTimeoutMs(message);
        if (System.currentTimeMillis() - lastHeartbeat <= timeout) {
            return true;
        }
        boolean connected = isConnectedByTagOrAlias(ip);
        if (connected && log.isDebugEnabled()) {
            log.debug("isOnline heartbeat stale but tcp alive, key={}, lastHeartbeatAgeMs={}, timeoutMs={}",
                    ip, System.currentTimeMillis() - lastHeartbeat, timeout);
        }
        return connected;
    }

    public void forceOffline(String machineTag) {
        if (machineTag == null || machineTag.isBlank()) {
            return;
        }
        String resolvedMachineTag = machineAliasToMachineTag.getOrDefault(machineTag, machineTag);
        machineTagTimeMap.remove(machineTag);
        machineTagMessageMap.remove(machineTag);
        machineTagAndLinkHostPortAnd.remove(machineTag);
        machineTagTimeMap.remove(resolvedMachineTag);
        machineTagMessageMap.remove(resolvedMachineTag);
        machineTagAndLinkHostPortAnd.remove(resolvedMachineTag);
        removeAliasesForMachineTag(resolvedMachineTag);
        dispatchStateStore.markMachineOffline(resolvedMachineTag);
        log.info("[Heartbeat] 手动标记机器离线: machineTag={}", resolvedMachineTag);
    }


    @Scheduled(fixedRate = 10_000) // 这个方法将每隔10秒执行一次
    public void heartbeatSave() {
        long now = System.currentTimeMillis();
        List<String> setOnlineMachine = new ArrayList<>();
        for (String machineTag : machineTagTimeMap.keySet()) {
            if (machineAliasToMachineTag.containsKey(machineTag)) {
                continue;
            }
            Long lastHeartbeat = machineTagTimeMap.get(machineTag);
            HeartbeatMessage message = machineTagMessageMap.get(machineTag);
            if (lastHeartbeat == null || message == null) {
                continue;
            }
            MachineInfoEntity machineInfoEntity = messageToEntity(message);
            long timeout = getOnlineTimeoutMs(message);
            if (now - lastHeartbeat > timeout && !isConnectedByTagOrAlias(machineTag)) {
                log.info("[Heartbeat] 机器下线: machineTag={}, lastHeartbeat={}ms前, timeout={}ms, linkIp={}, agentIp={}",
                        machineTag, now - lastHeartbeat, timeout, message.getLinkIp(), message.getIpAddress());

                List<MachineInfoEntity> machineInfoEntities =
                        machineInfoMapper.listEntity(
                                new MachineInfoQuery().where.machineTag()
                                        .eq(message.getMachineTag()).end()
                        );
                // 找到正在执行的任务，处理任务状态
                if (!machineInfoEntities.isEmpty()) {
                    processExecutingTask(machineInfoEntities.get(0));
                }
                processMainTask(machineTag);
                // 处理机器状态
                machineInfoEntity.setStatus("OFFLINE"); // 设置状态为离线
                machineInfoEntity.setExecuteStatus(CommonConstant.IDLE);
                machineInfoEntity.setTaskId(null);
                // 并且从map中删除
                machineTagTimeMap.remove(machineTag);
                machineTagMessageMap.remove(machineTag);
                machineTagAndLinkHostPortAnd.remove(machineTag);
                removeAliasesForMachineTag(machineTag);
                dispatchStateStore.clearMachine(machineTag);
            } else {
                machineInfoEntity.setStatus("ONLINE");
                DispatchStateRegistry.DispatchState dispatchState = dispatchStateStore.getDispatch(machineTag);
                if (dispatchState != null) {
                    machineInfoEntity.setExecuteStatus(CommonConstant.DISPATCHING);
                    machineInfoEntity.setTaskId(dispatchState.getTaskId());
                    machineInfoEntity.setActiveExecuteId(dispatchState.getExecuteId());
                    machineInfoEntity.setActiveDispatchToken(dispatchState.getDispatchToken());
                } else {
                    machineInfoEntity.setExecuteStatus(message.getExecuteStatus());// 设置状态为在线
                    machineInfoEntity.setTaskId(message.getRunningExecuteId() == null ? null : machineInfoEntity.getTaskId());
                }
                setOnlineMachine.add(machineTag);
            }
            HeartbeatEntity heartbeatEntity = new HeartbeatEntity();
            BeanUtils.copyProperties(machineInfoEntity, heartbeatEntity);
            machineInfoEntity.setContainerId(message.getDockerContainerId());
            machineInfoEntity.setContainerName(message.getDockerContainerName());
            machineInfoEntity.setIsDocker(message.getDockerContainer()?1:0);
            machineInfoEntity.setMachineTag(message.getMachineTag());
            machineInfoEntity.setLinkIp(message.getLinkIp());
            machineInfoEntity.setLinkPort(String.valueOf(message.getLinkPort()));
            machineInfoEntity.setTag(message.getTag());
            heartbeatMapper.insert(heartbeatEntity);
            // 判断是否已经存在
            MachineInfoEntity machineInfoByIp = machineInfoMapper.findOne(
                    new MachineInfoQuery().where.machineTag().eq(message.getMachineTag()).end()
            );
            if (machineInfoByIp != null) {
                machineInfoEntity.setId(machineInfoByIp.getId());
            }
            machineInfoMapper.saveOrUpdate(machineInfoEntity);
        }

        dispatchStateStore.markStaleIdleMachinesOffline(now - HEARTBEAT_IDLE_TIMEOUT_MS);

    }

    public   static String getIpAddressByMachineTag(String tag) {
        String resolved = machineAliasToMachineTag.getOrDefault(tag, tag);
        return machineTagAndLinkHostPortAnd.get(resolved);
    }
    private void processMainTask(String machineTag) {
        //把待调度的任务设置为create 状态，runing的设置为exception
        List<TaskExecutionEntity> taskExecutionEntities = taskExecutionMapper.listEntity(
                new TaskExecutionQuery().where.machineTag().eq(machineTag).isDeleted().eq(0).end()
        );
        for (TaskExecutionEntity taskExecutionEntity : taskExecutionEntities) {
            if (taskExecutionEntity.getExecuteStatus().equals(CommonConstant.RUNNING)) {
                taskExecutionEntity.setStatus(CommonConstant.EXCEPTION);
                taskExecutionEntity.setExecuteStatus(CommonConstant.EXCEPTION);
                taskExecutionEntity.setEndTime(new Date());
                taskExecutionEntity.setExecuteTime(
                        (int)(taskExecutionEntity.getEndTime().getTime()
                                - taskExecutionEntity.getStartTime().getTime())
                );
                taskExecutionEntity.setLogs("Task execution failed, machine lost connection");
                taskExecutionMapper.saveOrUpdate(taskExecutionEntity);
            } else if (taskExecutionEntity.getExecuteStatus().equals(CommonConstant.CREATE)
                    && taskExecutionEntity.getStatus().equals(CommonConstant.TO_BE_SCHEDULED)) {
                taskExecutionEntity.setStatus(CommonConstant.CREATE);
                taskExecutionEntity.setExecuteStatus(CommonConstant.CREATE);
                taskExecutionEntity.setEndTime(new Date());
                taskExecutionEntity.setExecuteTime(
                        (int)(taskExecutionEntity.getEndTime().getTime()
                                - taskExecutionEntity.getStartTime().getTime())
                );
                taskExecutionEntity.setLogs("Task execution failed, machine lost connection");
                taskExecutionMapper.saveOrUpdate(taskExecutionEntity);
            }
        }
    }






    private void processExecutingTask(MachineInfoEntity machineInfoEntity) {
        if (machineInfoEntity.getTaskId() == null) {
            return;
        }
        List<TaskExecutionEntity> taskExecutionEntities = taskExecutionMapper.listEntity(
                new TaskExecutionQuery().where.taskId().eq(machineInfoEntity.getTaskId()).isDeleted().eq(0)
                        .end()
        );
        // 找出 running 的任务
        for (TaskExecutionEntity taskExecutionEntity : taskExecutionEntities) {
            if (CommonConstant.RUNNING.equals(taskExecutionEntity.getExecuteStatus())
                    || CommonConstant.CREATE.equals(taskExecutionEntity.getExecuteStatus())) {

                // 从 Redis 中抢救日志
                String savedLogs = rescueLogsFromRedis(taskExecutionEntity.getId());

                taskExecutionEntity.setStatus(CommonConstant.EXCEPTION);
                taskExecutionEntity.setExecuteStatus(CommonConstant.EXCEPTION);
                taskExecutionEntity.setEndTime(new Date());
                if (taskExecutionEntity.getStartTime() != null) {
                    taskExecutionEntity.setExecuteTime(
                            (int) (System.currentTimeMillis() - taskExecutionEntity.getStartTime().getTime())
                    );
                }

                // 设置日志：已保存的日志 + 断连提示
                String finalLogs = savedLogs.isEmpty()
                        ? "Task execution failed, machine lost connection"
                        : savedLogs + "\n\n[Agent 断开连接，任务异常终止]";
                taskExecutionEntity.setLogs(finalLogs);

                taskExecutionMapper.saveOrUpdate(taskExecutionEntity);

                log.info("[Heartbeat] 任务因机器下线而终止: taskId={}, executeId={}, savedLogLines={}",
                        machineInfoEntity.getTaskId(), taskExecutionEntity.getId(),
                        savedLogs.isEmpty() ? 0 : savedLogs.split("\n").length);
            }
        }
        taskService.updateMainTask(machineInfoEntity.getTaskId());
    }

    /**
     * 从 Redis 中抢救任务日志
     * @param executeId 执行ID
     * @return 日志内容，如果没有则返回空字符串
     */
    private String rescueLogsFromRedis(Long executeId) {
        try {
            String key = String.valueOf(executeId);
            List<String> logs = stringRedisTemplate.opsForList().range(key, 0, -1);
            if (logs != null && !logs.isEmpty()) {
                // 清理 Redis 中的日志
                stringRedisTemplate.delete(key);
                // 去除每行末尾的 -idNo: xxx 标记
                return logs.stream()
                        .map(line -> {
                            int idx = line.lastIndexOf("-idNo: ");
                            return idx > 0 ? line.substring(0, idx) : line;
                        })
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
            }
        } catch (Exception e) {
            log.warn("[Heartbeat] 从 Redis 抢救日志失败: executeId={}, error={}", executeId, e.getMessage());
        }
        return "";
    }

    private MachineInfoEntity messageToEntity(HeartbeatMessage message) {
        MachineInfoEntity entity = new MachineInfoEntity();
        entity.setOperatingSystem(message.getOperatingSystem());
        entity.setProcessor(message.getProcessor());
        entity.setMemory(message.getMemory());
        entity.setCpuUsage(message.getCpuUsage());
        entity.setTotalMemory(message.getTotalMemory());
        entity.setAvailableMemory(message.getAvailableMemory());
        entity.setIpAddress(message.getIpAddress());
        entity.setLastUpdated(new Date());
        entity.setDiskUsage(message.getDiskUsage());
        entity.setAgentSessionId(message.getAgentSessionId());
        entity.setAgentStateVersion(message.getStateVersion() == null ? 0L : message.getStateVersion());
        entity.setRunningExecuteId(message.getRunningExecuteId());
        entity.setRunningDispatchToken(message.getDispatchToken());
        return entity;
    }

    private static String resolveMachineTag(HeartbeatMessage message, String linkIp) {
        // Docker 容器优先使用 containerId 作为标识
        if (message != null && Boolean.TRUE.equals(message.getDockerContainer())) {
            String containerId = message.getDockerContainerId();
            if (containerId != null && !containerId.isBlank()) {
                return containerId;
            }
        }
        // 非 Docker 环境优先使用 Server 端获取的连接 IP，避免 Agent 网卡识别错误
        if (linkIp != null && !linkIp.isBlank()
                && !linkIp.startsWith("127.")
                && !"0.0.0.0".equals(linkIp)) {
            return linkIp;
        }
        // 兜底使用 Agent 上报的 IP
        if (message != null) {
            String ipAddress = message.getIpAddress();
            if (ipAddress != null && !ipAddress.isBlank()
                    && !ipAddress.startsWith("127.")
                    && !"0.0.0.0".equals(ipAddress)) {
                return ipAddress;
            }
        }
        return linkIp;
    }

    private void registerAliases(HeartbeatMessage message, String machineTag, String linkIp, String linkHostPort, long now) {
        if (message == null) {
            return;
        }
        if (linkIp != null && !linkIp.isBlank() && !Objects.equals(linkIp, machineTag)) {
            machineAliasToMachineTag.put(linkIp, machineTag);
            machineTagAndLinkHostPortAnd.put(linkIp, linkHostPort);
            machineTagTimeMap.put(linkIp, now);
            machineTagMessageMap.put(linkIp, message);
        }
        String ipAddress = message.getIpAddress();
        if (ipAddress != null && !ipAddress.isBlank() && !Objects.equals(ipAddress, machineTag)) {
            machineAliasToMachineTag.put(ipAddress, machineTag);
            machineTagAndLinkHostPortAnd.put(ipAddress, linkHostPort);
            machineTagTimeMap.put(ipAddress, now);
            machineTagMessageMap.put(ipAddress, message);
        }
        String tag = message.getTag();
        if (tag != null && !tag.isBlank() && !Objects.equals(tag, machineTag)) {
            machineAliasToMachineTag.put(tag, machineTag);
        }
    }

    private void removeAliasesForMachineTag(String machineTag) {
        for (Map.Entry<String, String> entry : machineAliasToMachineTag.entrySet()) {
            if (Objects.equals(machineTag, entry.getValue())) {
                String alias = entry.getKey();
                machineAliasToMachineTag.remove(alias, machineTag);
                machineTagAndLinkHostPortAnd.remove(alias);
                machineTagTimeMap.remove(alias);
                machineTagMessageMap.remove(alias);
            }
        }
    }

    private static long getOnlineTimeoutMs(HeartbeatMessage message) {
        if (message == null) {
            return HEARTBEAT_IDLE_TIMEOUT_MS;
        }
        String status = message.getExecuteStatus();
        if (CommonConstant.RUNNING.equals(status) || "running".equalsIgnoreCase(status)) {
            return HEARTBEAT_RUNNING_TIMEOUT_MS;
        }
        return HEARTBEAT_IDLE_TIMEOUT_MS;
    }

    private boolean isConnectedByTagOrAlias(String key) {
        String remoteKey = getIpAddressByMachineTag(key);
        return ConnectionRegistry.isRemoteActive(remoteKey);
    }

}
