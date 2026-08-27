package com.hjmicro.service.impl;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import com.hjmicro.fluent.wrapper.*;
import com.hjmicro.service.impl.rpc.HeartbeatServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hjmicro.agent.service.TaskHandlerService;
import com.hjmicro.domain.CommonConstant;
import com.hjmicro.domain.SseTagEnum;
import com.hjmicro.domain.dto.TaskExecuteDTO;
import com.hjmicro.domain.request.RepeatTaskRequest;
import com.hjmicro.domain.vo.TaskEntityVO;
import com.hjmicro.fluent.entity.MachineInfoEntity;
import com.hjmicro.fluent.entity.TaskEntity;
import com.hjmicro.fluent.entity.TaskExecutionEntity;
import com.hjmicro.fluent.mapper.MachineInfoMapper;
import com.hjmicro.fluent.mapper.TaskExecutionMapper;
import com.hjmicro.fluent.mapper.TaskMapper;
import com.hjmicro.mq.RocketMQConstant;
import com.hjmicro.netty.ProxyConfiguration;
import com.hjmicro.service.DispatchStateRegistry;
import com.hjmicro.service.DispatchStateStore;
import com.hjmicro.service.SseService;
import com.hjmicro.service.TaskService;

@Service
public class TaskServiceImplSub implements TaskService {

    private static final Logger logger =
        LoggerFactory.getLogger(TaskServiceImplSub.class);

    // 创建线程池
    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        10, 20,
        100L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()
    );
    @Autowired
    TaskHandlerService taskHandlerService;
    @Autowired
    TaskExecutionMapper taskExecutionMapper;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private MachineInfoMapper machineInfoMapper;
    @Autowired
    private SseService sseService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private HeartbeatServiceImpl heartbeatService;
    @Autowired
    private DispatchStateStore dispatchStateStore;

    // 预期执行时间缓存，避免重复计算
    private static final Map<String, CachedExecutionTime> executionTimeCache = new ConcurrentHashMap<>();
    private static final long CACHE_EXPIRE_TIME = 5 * 60 * 1000; // 5分钟缓存过期时间

    /**
     * 缓存的执行时间数据
     */
    private static class CachedExecutionTime {
        private final long expectedTime;
        private final long cacheTime;
        private final String confidenceInterval;

        public CachedExecutionTime(long expectedTime, String confidenceInterval) {
            this.expectedTime = expectedTime;
            this.confidenceInterval = confidenceInterval;
            this.cacheTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - cacheTime > CACHE_EXPIRE_TIME;
        }

        public String getFormattedTime() {
            return confidenceInterval;
        }
    }

    @Override
    public Long doTask(JSONObject request) {
        request.getString("executableFilePath");
        JSONArray targetIps = request.getJSONArray("targetIps");

        if (CollectionUtils.isEmpty(targetIps)) {
            TaskEntity taskEntity = initTaskEntity(request, targetIps);
            taskEntity.setExecuteStatus(CommonConstant.FAIL);
            // 英文
            taskEntity.setMessage("targetIps is empty");
            taskEntity.setStatus(CommonConstant.FAIL);
            this.addTask(taskEntity);
            return taskEntity.getId();
        }

        Long firstTaskId = null;
        List<String> machineTags = new ArrayList<>();
        for (Object obj : targetIps) {
            String machineTag = (String)obj;
            JSONArray singleTarget = new JSONArray();
            singleTarget.add(machineTag);
            TaskEntity taskEntity = initTaskEntity(request, singleTarget);
            taskEntity.setMachineIps(machineTag);
            taskEntity.setMachineTag(machineTag);
            taskEntity.setAnnex("");
            this.addTask(taskEntity);
            if (firstTaskId == null) {
                firstTaskId = taskEntity.getId();
            }
            machineTags.add(machineTag);
        }
        machineTags.forEach(this::trySchedule);
        return firstTaskId;
    }


    /**
     * 加入队列
     * @param targetIps
     * @param taskEntity
     */
    private void joinQueue(JSONArray targetIps, TaskEntity taskEntity) {
        for (Object obj : targetIps) {
            String targetIp = (String)obj;
            taskEntity.setStatus(CommonConstant.CREATE);
            taskEntity.setExecuteStatus(CommonConstant.TO_BE_SCHEDULED);
            taskEntity.setMachineIps(targetIp);
            taskEntity.setMachineTag(targetIp);
            taskEntity.setAnnex("");
            this.updateTaskById(taskEntity);
            this.trySchedule(targetIp);
        }
    }

    private static final ConcurrentHashMap<String, Semaphore> lockMap = new ConcurrentHashMap<>();

    // 任务重试计数器 (taskId -> retryCount)
    private static final ConcurrentHashMap<Long, Integer> taskRetryCountMap = new ConcurrentHashMap<>();
    private static final int MAX_RETRY_COUNT = 3;

    @Override
    public Long executeNextTask(String machineTag) {
        return trySchedule(machineTag);
    }

    @Override
    public Long trySchedule(String machineTag) {
        if (machineTag == null || machineTag.isBlank()) {
            return null;
        }
        Semaphore semaphore = lockMap.computeIfAbsent(machineTag, k -> new Semaphore(1));
        boolean acquired = false;
        try {
            if (!semaphore.tryAcquire()) {
                logger.debug("机器调度锁获取失败 machineTag:{}", machineTag);
                return null;
            }
            acquired = true;
            if (dispatchStateStore.getDispatch(machineTag) != null) {
                return null;
            }
            reconcileStaleActiveTasks(machineTag);
            //判断当前是否有正在执行的任务
            List<TaskEntity> runningTaskEntities = taskMapper.listEntity(
                    new TaskQuery()
                            .where.machineTag().eq(machineTag)
                            .and.executeStatus().in(Arrays.asList(CommonConstant.RUNNING, CommonConstant.DISPATCHING))
                            .end()
            );
            if (!CollectionUtils.isEmpty(runningTaskEntities)){
                return null;
            }
            // 获取该IP下所有状态为TO_BE_SCHEDULED的任务，并按照权重和创建时间排序
            List<TaskEntity> taskEntities = taskMapper.listEntity(
                    new TaskQuery()
                            .where.machineTag().eq(machineTag)
                            .and.executeStatus().eq(CommonConstant.TO_BE_SCHEDULED)
                            .end()
                            .orderBy
                            .weight().asc() // 假设权重越低，优先级越高
                            .gmtCreated().asc() // 按创建时间升序排列
                            .end()
            );
            if (CollectionUtils.isEmpty(taskEntities)) {
                return null;
            }
            TaskEntity taskEntity = taskEntities.get(0);
            int updated = taskMapper.updateBy(
                    new TaskUpdate()
                            .where.id().eq(taskEntity.getId())
                            .and.executeStatus().eq(CommonConstant.TO_BE_SCHEDULED)
                            .end()
                            .set.executeStatus().is(CommonConstant.DISPATCHING)
                            .startTime().is(new Date())
                            .end()
            );
            if (updated <= 0) {
                return null;
            }
            if (!heartbeatService.isOnline(machineTag) || !dispatchStateStore.reserveMachine(machineTag, taskEntity.getId())) {
                taskMapper.updateBy(
                        new TaskUpdate()
                                .where.id().eq(taskEntity.getId())
                                .and.executeStatus().eq(CommonConstant.DISPATCHING)
                                .end()
                                .set.executeStatus().is(CommonConstant.TO_BE_SCHEDULED)
                                .end()
                );
                return null;
            }
            taskEntity.setExecuteStatus(CommonConstant.DISPATCHING);
            taskEntity.setStartTime(new Date());
            frontListen(taskEntity);
            // 执行找到的第一个任务
            executeTask(taskEntity, UUID.randomUUID().toString());
            return  taskEntity.getId();
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("机器执行任务异常 machineTag:{} trySchedule ",machineTag);
            return null;
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private void reconcileStaleActiveTasks(String machineTag) {
        List<TaskEntity> activeTasks = taskMapper.listEntity(
                new TaskQuery()
                        .where.machineTag().eq(machineTag)
                        .and.executeStatus().in(Arrays.asList(CommonConstant.RUNNING, CommonConstant.DISPATCHING))
                        .end()
        );
        if (CollectionUtils.isEmpty(activeTasks)) {
            return;
        }
        for (TaskEntity task : activeTasks) {
            DispatchStateRegistry.DispatchState dispatchState = dispatchStateStore.getDispatchByTaskId(task.getId());
            if (dispatchState != null) {
                continue;
            }
            List<TaskExecutionEntity> executions = taskExecutionMapper.listEntity(
                    new TaskExecutionQuery()
                            .where.taskId().eq(task.getId())
                            .and.isDeleted().eq(0)
                            .end()
            );
            boolean hasActiveExecution = executions.stream().anyMatch(execution ->
                    CommonConstant.DISPATCHING.equals(execution.getExecuteStatus())
                            || CommonConstant.RUNNING.equals(execution.getExecuteStatus())
            );
            if (hasActiveExecution) {
                continue;
            }
            Date endTime = new Date();
            TaskUpdate update = new TaskUpdate()
                    .where.id().eq(task.getId())
                    .and.executeStatus().in(Arrays.asList(CommonConstant.RUNNING, CommonConstant.DISPATCHING))
                    .end()
                    .set.executeStatus().is(CommonConstant.EXCEPTION)
                    .status().is(CommonConstant.EXCEPTION)
                    .endTime().is(endTime)
                    .message().is("Task marked exception: stale active task without active dispatch")
                    .end();
            if (task.getStartTime() != null) {
                update.set.executeTime().is((int) ((endTime.getTime() - task.getStartTime().getTime()) / 1000));
            }
            int updated = taskMapper.updateBy(update);
            if (updated > 0) {
                logger.warn("[Task] 修复脏 active 任务: taskId={}, machineTag={}, oldStatus={}, executions={}",
                        task.getId(), machineTag, task.getExecuteStatus(), executions.size());
                frontListen(taskMapper.findById(task.getId()));
            }
        }
    }

    @Override
    public boolean cancelTask(Long id) {
        TaskEntity taskEntity = taskMapper.findById(id);
        if (taskEntity == null) {
            return false;
        }
        DispatchStateRegistry.DispatchState dispatchState =
                dispatchStateStore.getDispatchByTaskId(id);
        // 修改状态
        taskEntity.setExecuteStatus(CommonConstant.CANCEL);
        taskEntity.setStatus(CommonConstant.CANCEL);
        taskEntity.setDesc("User Cancel Task");
        this.updateTaskById(taskEntity);
        
        // 同时取消相关的TaskExecutionEntity
        taskExecutionMapper.updateBy(
            new TaskExecutionUpdate()
                .where.taskId().eq(id)
                .and.isDeleted().eq(0)
                .end()
                .set.executeStatus().is(CommonConstant.CANCEL)
                .status().is(CommonConstant.CANCEL)
                .endTime().is(new Date())
                .end()
        );
        if (dispatchState != null) {
            executor.execute(() -> {
                try {
                    ProxyConfiguration.setRemoteMachineTag(dispatchState.getMachineTag());
                    taskHandlerService.cancelTask(dispatchState.getExecuteId(), dispatchState.getDispatchToken());
                } catch (Exception e) {
                    logger.warn("[Task] 取消 RPC 发送失败, taskId={}, executeId={}, error={}",
                            id, dispatchState.getExecuteId(), e.getMessage());
                } finally {
                    ProxyConfiguration.clearRemoteMachineTag();
                }
            });
        }
        
        return Boolean.TRUE;
    }

    @Override
    public boolean batchRepeat(List<Long> ids) {
        for (Long id : ids) {
            TaskEntity taskEntity = taskMapper.findById(id);
            if (taskEntity == null) {
                return false;
            }
            taskExecutionMapper.updateBy(
                    new TaskExecutionUpdate().where.taskId().eq(id).end()
                            .set.isDeleted().is(1).end()
            );
            // 修改状态
            taskEntity.setExecuteStatus(CommonConstant.TO_BE_SCHEDULED);
            taskEntity.setStatus(CommonConstant.CREATE);
            this.updateTaskById(taskEntity);
            JSONArray jsonArray = new JSONArray();
            jsonArray.add(taskEntity.getMachineIps());
            // 执行调度
            joinQueue(jsonArray, taskEntity);
        }
        return Boolean.TRUE;
    }


    /**
     * 执行任务
     * @param taskEntity
     */
    public void executeTask(TaskEntity taskEntity){
        executeTask(taskEntity, UUID.randomUUID().toString());
    }

    public void executeTask(TaskEntity taskEntity, String dispatchToken){
//        executor.execute(()->{
            TaskExecuteDTO taskExecuteDTO = new TaskExecuteDTO();
            taskExecuteDTO.setTaskId(taskEntity.getId());
            //二次检查，解锁后还需要判断是否在线
            boolean online = heartbeatService.isOnline(taskEntity.getMachineIps());
            if (!online){
                taskEntity.setExecuteStatus(CommonConstant.TO_BE_SCHEDULED);
                this.updateTaskById(taskEntity);
                releaseMachine(taskEntity.getMachineTag(), taskEntity.getId());
                return;
            }
            DispatchStateRegistry.AgentState agentState =
                    dispatchStateStore.getAgentState(taskEntity.getMachineTag());

            TaskExecutionEntity taskExecutionEntity =
                    initTaskExecutionEntity(taskEntity);

            taskExecutionEntity.setIpAddress(taskEntity.getMachineIps());
            taskExecutionEntity.setMachineTag(taskEntity.getMachineTag());
            taskExecutionEntity.setExecuteStatus(CommonConstant.DISPATCHING);
            taskExecutionEntity.setDispatchToken(dispatchToken);
            taskExecutionEntity.setDispatchBaseAgentSessionId(
                    agentState == null ? null : agentState.getAgentSessionId()
            );
            taskExecutionEntity.setDispatchBaseStateVersion(
                    agentState == null ? 0L : agentState.getStateVersion()
            );
            taskExecutionEntity.setDispatchTime(new Date());
            taskExecutionMapper.insert(taskExecutionEntity);
            dispatchStateStore.startDispatch(
                    taskEntity.getMachineTag(),
                    taskEntity.getId(),
                    taskExecutionEntity.getId(),
                    dispatchToken,
                    agentState
            );

            try {
                taskExecuteDTO.setExecuteId(taskExecutionEntity.getId());
                taskExecuteDTO.setIpAddress(taskEntity.getMachineIps());
                taskExecuteDTO.setExecutableFilePath(taskEntity.getCommand());
                taskExecuteDTO.setDispatchToken(dispatchToken);
                taskExecuteDTO.setRequestId(taskEntity.getRequestId());
                // 指定ip
                // 标记机器状态
                List<MachineInfoEntity> machineInfoEntities =
                        machineInfoMapper.listEntity(
                                new MachineInfoQuery().where.machineTag().eq(taskEntity.getMachineTag())
                                        .end()
                        );
                if (!CollectionUtils.isEmpty(machineInfoEntities)) {
                    MachineInfoEntity machineInfoEntity = machineInfoEntities.get(0);
                    machineInfoEntity.setExecuteStatus(CommonConstant.DISPATCHING);
                    machineInfoEntity.setTaskId(taskEntity.getId());
                    machineInfoMapper.updateById(machineInfoEntity);
                }
                final Long taskId = taskEntity.getId();
                final Long executeId = taskExecutionEntity.getId();
                final String machineTag = taskEntity.getMachineTag();

                executor.execute(()-> {
                    try {
                        ProxyConfiguration.setRemoteMachineTag(machineTag);
                        taskHandlerService.doTask(taskExecuteDTO);
                    } catch (RuntimeException e) {
                        // RPC 超时或连接失败，回滚任务状态
                        logger.error("[Task] RPC调用失败, taskId={}, executeId={}, machineTag={}, error={}",
                                taskId, executeId, machineTag, e.getMessage());
                        handleRpcFailure(taskId, executeId, machineTag, dispatchToken, e);
                    } finally {
                        ProxyConfiguration.clearRemoteMachineTag();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                handleError(taskExecutionEntity, e);
                dispatchStateStore.clearIfCurrent(
                        taskEntity.getMachineTag(),
                        taskEntity.getId(),
                        taskExecutionEntity.getId(),
                        dispatchToken
                );
                this.updateMainTask(taskEntity.getId());
            }

//        });

    }

    public Boolean updateMainTask(Long taskId) {
        List<TaskExecutionEntity> taskExecutionEntities = taskExecutionMapper
            .listEntity(new TaskExecutionQuery().where.isDeleted().eq(0).taskId().eq(taskId).end());

        TaskEntity taskEntity = taskMapper.findById(taskId);

        if (CollectionUtils.isEmpty(taskExecutionEntities)) {
            taskEntity.setExecuteStatus(CommonConstant.EXCEPTION);
            taskEntity.setEndTime(new Date());
            taskEntity.setMessage("No resources available for scheduling");
            this.updateTaskById(taskEntity);
            // 发送消息
            sendTaskStatusMsg(taskEntity);
            return false;
        }

        String status;
        Date latestEndTime = null;

        if (taskExecutionEntities.size() == 1) {
            TaskExecutionEntity entity = taskExecutionEntities.get(0);
            status = entity.getExecuteStatus().equals("end") ? entity.getStatus()
                : entity.getExecuteStatus();
            latestEndTime = entity.getEndTime();
        } else {
            List<TaskExecutionEntity> taskByRunning = taskExecutionEntities
                .stream()
                .filter(
                    taskExecutionEntity -> Objects.equals(
                        taskExecutionEntity.getExecuteStatus(), CommonConstant.RUNNING
                    )
                ).toList();

//            if (
//                taskByRunning.size() > 0 || taskExecutionEntities.size() != JSONArray
//                    .parseArray(taskEntity.getMachineIps()).size()
//            ) {
//                status = CommonConstant.RUNNING;
//            } else {
                int successCount = (int)taskExecutionEntities
                    .stream()
                    .filter(
                        entity -> entity.getExecuteStatus().equals("end")
                            && entity.getStatus().equals(CommonConstant.SUCCESS)
                    )
                    .count();
                int failCount = (int)taskExecutionEntities
                    .stream()
                    .filter(
                        entity -> entity.getExecuteStatus().equals("end") &&
                            (entity.getStatus().equals(CommonConstant.FAIL) ||
                                entity.getStatus().equals(CommonConstant.BLOCKED) ||
                                entity.getStatus().equals(CommonConstant.EXCEPTION))
                    )
                    .count();

                if (successCount == taskExecutionEntities.size()) {
                    status = CommonConstant.SUCCESS;
                } else if (failCount == taskExecutionEntities.size()) {
                    status = CommonConstant.FAIL;
                } else {
                    status = CommonConstant.PART_SUCCESS;
                }

                latestEndTime = taskExecutionEntities.stream()
                    .map(TaskExecutionEntity::getEndTime)
                        .filter(Objects::nonNull)
                    .max(Date::compareTo)
                    .orElse(null);
//            }
        }

        taskEntity.setExecuteStatus(status);
        if (latestEndTime != null && !latestEndTime.equals(new Date(Long.MIN_VALUE))) {
            taskEntity.setExecuteTime(
                (int)((latestEndTime.getTime() - taskEntity.getStartTime().getTime())
                    / 1000)
            );
        }
        taskEntity.setEndTime(latestEndTime);
        this.updateTaskById(taskEntity);
        
        // 任务完成时清除相关缓存，确保下次计算使用最新数据
        if (status.equals(CommonConstant.SUCCESS) || status.equals(CommonConstant.FAIL) || status.equals(CommonConstant.PART_SUCCESS)) {
            clearTaskCache(taskEntity.getName());
            // 清理重试计数器
            taskRetryCountMap.remove(taskId);
        }
        
        // 发送消息
        sendTaskStatusMsg(taskEntity);
        return true;
    }

    private void sendTaskStatusMsg(TaskEntity taskEntity) {
        try {
            // 这里要拼接下所有机器的日志信息
            List<TaskExecutionEntity> taskExecutionEntities =
                taskExecutionMapper.listEntity(
                    new TaskExecutionQuery().where.taskId().eq(taskEntity.getId()).isDeleted().eq(0).end()
                );
            StringBuilder msg = new StringBuilder();
            for (TaskExecutionEntity taskExecutionEntity : taskExecutionEntities) {
                msg.append(taskExecutionEntity.getIpAddress()).append(":")
                    .append(taskExecutionEntity.getLogs()).append("\n");
            }
            JSONObject jsonObject =
                JSONObject.parseObject(JSONObject.toJSONString(taskEntity));
            jsonObject.put("sendTime", new Date());
            jsonObject.put("message", msg.toString());
            // 使用字段保证消息的顺序性
            String hashKey = String.valueOf(taskEntity.getId()); // 使用任务ID作为hashKey
            rocketMQTemplate.syncSendOrderly(
                RocketMQConstant.TASK_STATUS_TOPIC + ":" + taskEntity.getRequestId(),
                jsonObject,
                hashKey
            );
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
    }

    @Override
    public List<TaskEntityVO> listTaskByRequestId(String requestId) {
        List<TaskEntity> taskEntities =
            taskMapper.listEntity(new TaskQuery().where.requestId().eq(requestId).end());
        List<TaskEntityVO> result = getTaskEntityVOS(taskEntities);
        return result;
    }

    @Override
    public List<TaskEntityVO> listTask(String requestId) {
        // 清理过期缓存
        cleanExpiredCache();
        
        List<TaskEntity> taskEntities =
                taskMapper.listEntity(new TaskQuery().where.requestId().eq(requestId).end());
        List<TaskEntityVO> result = getTaskEntityVOS(taskEntities);

        Map<String, List<TaskEntityVO>> taskEntityVOMap =
                result.stream().collect(Collectors.groupingBy(TaskEntityVO::getName));
        //taskEntities 先过滤出正在执行的，然后根据name找出执行完毕并且pass的用例，过滤掉最高和最低的20%，然后根据starttime
        // 和end算出预期时间
        List<String> names =
                result.stream().filter(item -> item.getExecuteStatus().equals(CommonConstant.RUNNING)).map(TaskEntityVO::getName).toList();

        if (!CollectionUtils.isEmpty(names)) {
            // 使用优化的预期时间计算算法
            for (String taskName : names) {
                String expectedTime = calculateExpectedExecutionTimeFormatted(taskName);
                List<TaskEntityVO> taskEntityVOs = taskEntityVOMap.get(taskName);
                if (taskEntityVOs != null) {
                    taskEntityVOs.forEach(item -> {
                        item.setExpectEndTime(expectedTime);
                    });
                }
            }
        }

        List<MachineInfoEntity> machineInfoEntities = machineInfoMapper.listEntity(new MachineInfoQuery());
        Map<String, List<MachineInfoEntity>> machineInfoEntityMap =
                machineInfoEntities.stream().collect(Collectors.groupingBy(MachineInfoEntity::getMachineTag));

        for (TaskEntityVO taskEntityVO : result) {
            List<MachineInfoEntity> machineInfoEntity = machineInfoEntityMap.get(taskEntityVO.getMachineTag());
            if (!CollectionUtils.isEmpty(machineInfoEntity)) {
                taskEntityVO.setMachineInfoEntity(machineInfoEntity.get(0));
            }
        }

        return result;
    }

    private List<TaskEntityVO> getTaskEntityVOS(List<TaskEntity> taskEntities) {
        if (CollectionUtils.isEmpty(taskEntities)) {
            return Collections.emptyList();
        }
        List<Long> taskIds = taskEntities.stream().map(TaskEntity::getId).toList();
        List<TaskExecutionEntity> taskExecutionEntities =
                taskExecutionMapper
                        .listEntity(new TaskExecutionQuery().where.isDeleted().eq(0).taskId().in(taskIds).end());

        Map<Long, List<TaskExecutionEntity>> taskByMachine =
            taskExecutionEntities.stream().peek(item->item.setLogs(null))
                .collect(Collectors.groupingBy(TaskExecutionEntity::getTaskId));

        List<MachineInfoEntity> machineInfoEntities = machineInfoMapper.listEntity(new MachineInfoQuery());
        Map<String, List<MachineInfoEntity>> machineInfoEntityMap =
                machineInfoEntities.stream().collect(Collectors.groupingBy(MachineInfoEntity::getMachineTag));


        return taskEntities.stream().map(item -> {
            TaskEntityVO taskEntityVO = new TaskEntityVO();
            taskEntityVO.setMachineExecuteStatus(taskByMachine.get(item.getId()));
            taskEntityVO.setMachineInfoEntity(
                machineInfoEntityMap.get(item.getMachineTag()).get(0)
            );
            BeanUtils.copyProperties(item, taskEntityVO);
            return taskEntityVO;
        }).toList();
    }

    @Override
    public Boolean updateTaskById(TaskEntity taskEntity) {
        // 收口主要是为了前端应用的监听
        taskMapper.updateById(taskEntity);
        frontListen(taskEntity);
        return true;
    }

    @Override
    public TaskEntity addTask(TaskEntity taskEntity) {
        int insert = taskMapper.insert(taskEntity);
        frontListen(taskEntity);
        return taskEntity;
    }

    @Override
    public void initLogByExecuteId(String executeId,String normalEnd) {
        List<String> lines = getLogLinesByExecuteId(executeId, normalEnd);
        if (!lines.isEmpty()) {
            sseService.send(SseTagEnum.EXECUTE_INFO.getCode(), executeId, String.join("\r\n", lines));
        }

    }

    @Override
    public List<String> getLogLinesByExecuteId(String executeId, String normalEnd) {
        if (executeId == null || executeId.isBlank()) {
            return Collections.emptyList();
        }
        if ("false".equals(normalEnd)) {
            TaskEntity taskEntity = taskMapper.findById(executeId);
            if (taskEntity == null || taskEntity.getMessage() == null) {
                return Collections.emptyList();
            }
            return splitLogLines(taskEntity.getMessage());
        }

        TaskExecutionEntity execution = taskExecutionMapper.findById(executeId);
        if (execution == null) {
            return Collections.emptyList();
        }

        List<String> redisLogs = redisTemplate.opsForList().range(executeId, -1000, -1);
        if (redisLogs != null && !redisLogs.isEmpty()) {
            return redisLogs;
        }

        if (execution.getLogs() == null || execution.getLogs().isBlank()) {
            return Collections.emptyList();
        }
        return splitLogLines(execution.getLogs());
    }

    private List<String> splitLogLines(String logs) {
        if (logs == null || logs.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(logs.split("\\r?\\n"))
                .filter(line -> line != null && !line.isBlank())
                .collect(Collectors.toList());
    }

    @Override
    public TaskEntityVO getTaskDetail(String taskId) {
        List<TaskEntity> taskEntities =
            taskMapper.listEntity(new TaskQuery().where.requestId().eq(taskId).end());
        if (CollectionUtils.isEmpty(taskEntities)) {
            return null;
        }

        Date earliestStartTime = null;
        Date latestEndTime = null;
        Set<String> operators = new HashSet<>();
        String status = "success";
        boolean isAnyTaskRunning = false;
        boolean isAnyTaskFailed = false;
        boolean isAnyTaskSuccess = false;
        for (TaskEntity task : taskEntities) {
            if (
                earliestStartTime == null || task.getStartTime().before(earliestStartTime)
            ) {
                earliestStartTime = task.getStartTime();
            }

            if (task.getEndTime() != null) {
                if (latestEndTime == null || task.getEndTime().after(latestEndTime)) {
                    latestEndTime = task.getEndTime();
                }
            }

            operators.add(task.getOperator());

            if ("to be scheduled".equalsIgnoreCase(task.getExecuteStatus())) {
                isAnyTaskRunning = true;
            } else if ("fail".equalsIgnoreCase(task.getExecuteStatus())) {
                isAnyTaskFailed = true;
            } else {
                isAnyTaskSuccess = true;
            }
        }

        if (isAnyTaskRunning) {
            status = "running";
            latestEndTime = null; // don't record end time if any task is running
        } else if (isAnyTaskFailed && isAnyTaskSuccess) {
            status = "partial failure";
        } else if (isAnyTaskFailed) {
            status = "fail";
        } else {
            status = "success";
        }

        TaskEntityVO taskDetails = new TaskEntityVO();
        taskDetails.setStartTime(earliestStartTime);
        taskDetails.setEndTime(latestEndTime);
        taskDetails.setOperator(String.join(",", operators));
        if (latestEndTime != null) {
            taskDetails.setExecuteTime(
                (int)(latestEndTime.getTime() - earliestStartTime.getTime())
            );
        }
        taskDetails.setExecuteStatus(status);

        return taskDetails;
    }

    @Override
    public Boolean repeatTask(RepeatTaskRequest request) {
        TaskEntity task = request.getTask();
        // 删除历史执行
        taskExecutionMapper.updateBy(
            new TaskExecutionUpdate().where.taskId().eq(request.getTask().getId()).end()
                    .set.isDeleted().is(1).end()
        );

        JSONArray jsonArray = new JSONArray();
        jsonArray.add(request.getMachineInfoEntity().getMachineTag());
        System.out.println("开始重试" + request.getTask().getDesc());
        // 执行调度
        joinQueue(jsonArray, request.getTask());
        return Boolean.TRUE;
    }


    @Override
    public void noticeFrontByIp(String ip){
        for (TaskEntity taskEntity :
                taskMapper.listEntity(new TaskQuery().where.machineIps().eq(ip).end())) {
            frontListen(taskEntity);
        }
    }

    @Override
    public boolean stopRunningTask(Long id) {
        return cancelTask(id);
    }

    @Override
    public boolean clearAgentRunningTask(String machineTag) {
        return operateAgent(machineTag, false);
    }

    @Override
    public boolean restartAgent(String machineTag) {
        return operateAgent(machineTag, true);
    }

    private boolean operateAgent(String machineTag, boolean restart) {
        if (machineTag == null || machineTag.isBlank()) {
            return false;
        }
        Semaphore semaphore = lockMap.computeIfAbsent(machineTag, k -> new Semaphore(1));
        boolean acquired = false;
        boolean rpcOk = false;
        boolean cleanupOk = true;
        boolean shouldSchedule = false;
        try {
            semaphore.acquire();
            acquired = true;
            DispatchStateRegistry.DispatchState dispatchState = dispatchStateStore.getDispatch(machineTag);

            try {
                ProxyConfiguration.setRemoteMachineTag(machineTag);
                rpcOk = Boolean.TRUE.equals(restart ? taskHandlerService.restartAgent() : taskHandlerService.stopTask());
            } catch (Exception e) {
                logger.warn("[AgentOps] {} RPC 失败, machineTag={}, error={}",
                        restart ? "restart" : "clear", machineTag, e.getMessage());
            } finally {
                ProxyConfiguration.clearRemoteMachineTag();
            }

            if (!rpcOk) {
                cleanupOk = false;
                logger.warn("[AgentOps] {} RPC 未确认，保留 Server active 状态: machineTag={}",
                        restart ? "restart" : "clear", machineTag);
            } else if (dispatchState != null) {
                cleanupOk = cancelActiveDispatch(
                        dispatchState,
                        restart ? "Agent restarted by operator" : "Agent running task cleared by operator"
                );
            } else {
                dispatchStateStore.clearMachine(machineTag);
                logger.info("[AgentOps] 未找到 active dispatch，已清理机器占用: machineTag={}", machineTag);
            }

            if (restart && rpcOk) {
                heartbeatService.forceOffline(machineTag);
            }
            logger.info("[AgentOps] {} 完成, machineTag={}, rpcOk={}, cleanupOk={}",
                    restart ? "restart" : "clear", machineTag, rpcOk, cleanupOk);
            shouldSchedule = !restart && rpcOk && cleanupOk;
            return rpcOk && cleanupOk;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[AgentOps] 操作被中断, machineTag={}, restart={}", machineTag, restart);
            return false;
        } finally {
            if (acquired) {
                semaphore.release();
            }
            if (shouldSchedule) {
                executor.execute(() -> trySchedule(machineTag));
            }
        }
    }

    private boolean cancelActiveDispatch(DispatchStateRegistry.DispatchState dispatchState, String reason) {
        Date endTime = new Date();
        TaskExecutionEntity execution = taskExecutionMapper.findById(dispatchState.getExecuteId());
        TaskExecutionUpdate update = new TaskExecutionUpdate()
                .where.id().eq(dispatchState.getExecuteId())
                .and.dispatchToken().eq(dispatchState.getDispatchToken())
                .and.executeStatus().in(Arrays.asList(CommonConstant.DISPATCHING, CommonConstant.RUNNING))
                .end()
                .set.executeStatus().is(CommonConstant.CANCEL)
                .status().is(CommonConstant.CANCEL)
                .endTime().is(endTime)
                .logs().is(reason)
                .end();
        if (execution != null && execution.getStartTime() != null) {
            update.set.executeTime().is((int) ((endTime.getTime() - execution.getStartTime().getTime()) / 1000));
        }
        int updated = taskExecutionMapper.updateBy(update);
        dispatchStateStore.clearIfCurrent(
                dispatchState.getMachineTag(),
                dispatchState.getTaskId(),
                dispatchState.getExecuteId(),
                dispatchState.getDispatchToken()
        );
        if (updated > 0 && dispatchState.getTaskId() != null) {
            updateMainTask(dispatchState.getTaskId());
            return true;
        }
        if (dispatchState.getTaskId() != null) {
            updateMainTask(dispatchState.getTaskId());
        }
        logger.info("[AgentOps] active dispatch 未更新，可能已进入终态: machineTag={}, executeId={}",
                dispatchState.getMachineTag(), dispatchState.getExecuteId());
        return true;
    }

    // 前端监听
    public void frontListen(TaskEntity taskEntity) {
        List<TaskEntityVO> taskEntityVOS = this.listTask(taskEntity.getRequestId());
        sseService.send(SseTagEnum.TASK_LIST.getCode(), "all", taskEntityVOS);
        List<TaskEntityVO> list =
            taskEntityVOS.stream()
                .filter(
                    item -> Objects.equals(item.getRequestId(), taskEntity.getRequestId())
                ).toList();
        sseService.send(SseTagEnum.TASK_LIST.getCode(), taskEntity.getRequestId(), list);
    }

    private TaskEntity initTaskEntity(JSONObject request, JSONArray targetIps) {
        String requestId = request.getString("requestId");
        String taskName = request.getString("taskName");
        String taskDesc = request.getString("taskDesc");
        String operator = request.getString("operator");
        String command = request.getString("executableFilePath");
        String conditionConfig = request.getString("conditionConfig");

        TaskEntity taskEntity = new TaskEntity();
        taskEntity.setCommand(command);
        taskEntity.setRequestId(requestId);
        taskEntity.setConditionConfig(conditionConfig);
        taskEntity.setName(taskName);
        taskEntity.setDesc(taskDesc);
        taskEntity.setOperator(operator);
        taskEntity.setStartTime(new Date());
        taskEntity.setStatus(CommonConstant.CREATE);
        taskEntity.setExecuteStatus("to be scheduled");
        if (!CollectionUtils.isEmpty(targetIps)) {
            taskEntity.setMachineIps(targetIps.getString(0));
            taskEntity.setMachineTag(targetIps.getString(0));
        }
        return taskEntity;
    }

    private TaskExecutionEntity initTaskExecutionEntity(TaskEntity taskEntity) {
        TaskExecutionEntity entity = new TaskExecutionEntity();
        entity.setExecuteStatus(CommonConstant.TO_BE_SCHEDULED);
        entity.setTaskId(taskEntity.getId());
        entity.setStartTime(new Date());
        return entity;
    }

    private void handleError(TaskExecutionEntity entity, Exception e) {
        entity.setExecuteStatus(CommonConstant.EXCEPTION);
        entity.setLogs(e.getMessage());
        entity.setStatus(CommonConstant.EXCEPTION);
        entity.setEndTime(new Date());
        entity.setExecuteTime(
            (int)((new Date().getTime() - entity.getStartTime().getTime()) / 1000)
        );
        taskExecutionMapper.updateById(entity);
        logger.error("Error during task execution", e);
        // 机器解锁
    }

    /**
     * 处理 RPC 调用失败（超时、连接断开等）
     * 将任务回滚为待调度状态，等待重新调度
     */
    private void handleRpcFailure(Long taskId, Long executeId, String machineTag, String dispatchToken, Exception e) {
        Semaphore semaphore = lockMap.computeIfAbsent(machineTag, k -> new Semaphore(1));
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;

            // 更新 TaskExecution 状态
            TaskExecutionEntity execution = taskExecutionMapper.findById(executeId);
            if (execution != null && dispatchStateStore.isCurrent(executeId, dispatchToken)
                    && (CommonConstant.TO_BE_SCHEDULED.equals(execution.getExecuteStatus())
                    || CommonConstant.DISPATCHING.equals(execution.getExecuteStatus()))) {
                // 任务还未开始执行，标记为 RPC 失败
                execution.setExecuteStatus(CommonConstant.EXCEPTION);
                execution.setStatus(CommonConstant.EXCEPTION);
                execution.setEndTime(new Date());
                execution.setLogs("RPC调用失败: " + e.getMessage());
                if (execution.getStartTime() != null) {
                    execution.setExecuteTime((int) (System.currentTimeMillis() - execution.getStartTime().getTime()));
                }
                taskExecutionMapper.updateById(execution);
            }

            // 更新主任务状态，回滚为待调度（允许重试）
            TaskEntity task = taskMapper.findById(taskId);
            if (task != null && dispatchStateStore.isCurrent(executeId, dispatchToken)
                    && (CommonConstant.RUNNING.equals(task.getExecuteStatus())
                    || CommonConstant.DISPATCHING.equals(task.getExecuteStatus()))) {
                // 检查重试次数
                int retryCount = taskRetryCountMap.getOrDefault(taskId, 0);

                if (retryCount < MAX_RETRY_COUNT) {
                    // 允许重试，回滚状态
                    taskRetryCountMap.put(taskId, retryCount + 1);
                    task.setExecuteStatus(CommonConstant.TO_BE_SCHEDULED);
                    task.setMessage("RPC失败，等待重试 (" + (retryCount + 1) + "/" + MAX_RETRY_COUNT + "): " + e.getMessage());
                    logger.info("[Task] RPC失败，任务回滚待重试: taskId={}, retryCount={}", taskId, retryCount + 1);
                } else {
                    // 超过重试次数，标记为失败
                    taskRetryCountMap.remove(taskId);
                    task.setExecuteStatus(CommonConstant.EXCEPTION);
                    task.setStatus(CommonConstant.EXCEPTION);
                    task.setEndTime(new Date());
                    task.setMessage("RPC失败，已达最大重试次数: " + e.getMessage());
                    logger.warn("[Task] RPC失败，超过最大重试次数: taskId={}", taskId);
                }
                taskMapper.updateById(task);
                frontListen(task);
            }

            dispatchStateStore.clearIfCurrent(machineTag, taskId, executeId, dispatchToken);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.error("[Task] handleRpcFailure interrupted, taskId={}", taskId);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private void releaseMachine(String machineTag, Long taskId) {
        dispatchStateStore.releaseMachine(machineTag, taskId);
    }

    /**
     * 计算任务预期执行时间
     * 使用改进的统计方法，包括异常值检测、加权平均和置信区间
     * 
     * @param taskName 任务名称
     * @return 预期执行时间（秒）
     */
    private long calculateExpectedExecutionTime(String taskName) {
        // 获取历史成功执行的任务数据
        List<TaskEntity> historicalTasks = taskMapper.listEntity(
            new TaskQuery()
                .where.name().eq(taskName)
                .and.executeStatus().eq(CommonConstant.SUCCESS)
                .and.executeTime().gt(0) // 确保执行时间有效
                .end()
                .orderBy.gmtCreated().desc() // 按创建时间倒序，优先考虑最近的数据
                .end()
        );

        if (CollectionUtils.isEmpty(historicalTasks)) {
            return 0L; // 没有历史数据
        }

        // 限制历史数据量，避免计算过慢
        if (historicalTasks.size() > 100) {
            historicalTasks = historicalTasks.subList(0, 100);
        }

        // 提取执行时间数据
        List<Long> executionTimes = historicalTasks.stream()
            .map(TaskEntity::getExecuteTime)
            .filter(time -> time != null && time > 0)
                //转换成Long
            .map(Long::valueOf)
            .toList();

        if (executionTimes.isEmpty()) {
            return 0L;
        }

        // 如果数据量太少，直接返回平均值
        if (executionTimes.size() < 3) {
            return (long) executionTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
        }

        // 计算四分位数用于异常值检测
        List<Long> sortedTimes = new ArrayList<>(executionTimes);
        Collections.sort(sortedTimes);
        
        int n = sortedTimes.size();
        long q1 = sortedTimes.get(n / 4); // 第一四分位数
        long q3 = sortedTimes.get(3 * n / 4); // 第三四分位数
        long iqr = q3 - q1; // 四分位距
        
        // 定义异常值边界（使用1.5倍IQR规则）
        long lowerBound = q1 - (long)(1.5 * iqr);
        long upperBound = q3 + (long)(1.5 * iqr);
        
        // 过滤异常值
        List<Long> filteredTimes = sortedTimes.stream()
            .filter(time -> time >= lowerBound && time <= upperBound)
            .collect(Collectors.toList());

        // 如果过滤后数据太少，使用原始数据
        if (filteredTimes.size() < 2) {
            filteredTimes = sortedTimes;
        }

        // 计算加权平均值，最近的数据权重更高
        double totalWeight = 0.0;
        double weightedSum = 0.0;
        
        for (int i = 0; i < filteredTimes.size(); i++) {
            // 使用指数衰减权重，最近的数据权重最高
            double weight = Math.exp(-0.1 * i); // 衰减因子0.1
            weightedSum += filteredTimes.get(i) * weight;
            totalWeight += weight;
        }

        long expectedTime = totalWeight > 0 ? (long)(weightedSum / totalWeight) : 0L;

        // 添加置信区间（±10%）
        long confidenceInterval = (long)(expectedTime * 0.1);
        
        // 返回预期时间（可以根据需要调整是否包含置信区间）
        return expectedTime;
    }

    /**
     * 计算任务预期执行时间（带置信区间，带缓存）
     * 
     * @param taskName 任务名称
     * @return 包含预期时间和置信区间的字符串
     */
    private String calculateExpectedExecutionTimeWithConfidence(String taskName) {
        // 检查缓存
        CachedExecutionTime cached = executionTimeCache.get(taskName);
        if (cached != null && !cached.isExpired()) {
            return cached.getFormattedTime();
        }
        
        long expectedTime = calculateExpectedExecutionTime(taskName);
        
        if (expectedTime == 0) {
            return "0";
        }
        
        // 计算置信区间（±15%）
        long confidenceInterval = (long)(expectedTime * 0.15);
        String formattedTime = String.format("%d±%d", expectedTime, confidenceInterval);
        
        // 更新缓存
        executionTimeCache.put(taskName, new CachedExecutionTime(expectedTime, formattedTime));
        
        return formattedTime;
    }

    /**
     * 清理过期的缓存
     */
    private void cleanExpiredCache() {
        executionTimeCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * 清除指定任务名称的缓存
     * 
     * @param taskName 任务名称
     */
    private void clearTaskCache(String taskName) {
        executionTimeCache.remove(taskName);
    }

    /**
     * 清除所有缓存
     */
    private void clearAllCache() {
        executionTimeCache.clear();
    }

    /**
     * 格式化执行时间为友好的显示格式
     * 
     * @param seconds 秒数
     * @return 格式化后的时间字符串
     */
    private String formatExecutionTime(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        
        StringBuilder result = new StringBuilder();
        if (hours > 0) {
            result.append(hours).append("h");
        }
        if (minutes > 0) {
            result.append(minutes).append("m");
        }
        if (secs > 0 || result.length() == 0) {
            result.append(secs).append("s");
        }
        
        return result.toString();
    }

    /**
     * 计算任务预期执行时间（带置信区间，带缓存，格式化显示）
     * 
     * @param taskName 任务名称
     * @return 包含预期时间和置信区间的格式化字符串
     */
    private String calculateExpectedExecutionTimeFormatted(String taskName) {
        // 检查缓存
        CachedExecutionTime cached = executionTimeCache.get(taskName);
        if (cached != null && !cached.isExpired()) {
            return cached.getFormattedTime();
        }
        
        long expectedTime = calculateExpectedExecutionTime(taskName);
        
        if (expectedTime == 0) {
            return "0s";
        }
        
        // 计算置信区间（±15%）
        long confidenceInterval = (long)(expectedTime * 0.15);
        String formattedTime = String.format("%s±%s", 
            formatExecutionTime(expectedTime), 
            formatExecutionTime(confidenceInterval));
        
        // 更新缓存
        executionTimeCache.put(taskName, new CachedExecutionTime(expectedTime, formattedTime));
        
        return formattedTime;
    }
}
