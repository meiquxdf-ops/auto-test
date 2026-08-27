package com.hjmicro.service.impl.rpc;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.hjmicro.domain.CommonConstant;
import com.hjmicro.domain.SseTagEnum;
import com.hjmicro.domain.StatusResult;
import com.hjmicro.domain.dto.TaskExecuteInfo;
import com.hjmicro.fluent.entity.TaskEntity;
import com.hjmicro.fluent.entity.TaskExecutionEntity;
import com.hjmicro.fluent.mapper.MachineInfoMapper;
import com.hjmicro.fluent.mapper.TaskExecutionMapper;
import com.hjmicro.fluent.mapper.TaskMapper;
import com.hjmicro.fluent.wrapper.MachineInfoUpdate;
import com.hjmicro.netty.MachineLock;
import com.hjmicro.service.DispatchStateRegistry;
import com.hjmicro.service.DispatchStateStore;
import com.hjmicro.server.service.TaskRpcService;
import com.hjmicro.service.SseService;
import com.hjmicro.service.TaskService;
import org.springframework.data.redis.core.StringRedisTemplate;

@Service
public class TaskRpcServiceImpl implements TaskRpcService {

    private static final Logger logger = LoggerFactory.getLogger(TaskRpcServiceImpl.class);
    
    // 常量定义
    private static final String ID_NO_SEPARATOR = "-idNo: ";
    private static final String DEFAULT_OTHER_MESSAGE = "Default status. No specific condition matched.";
    private static final String CONDITION_KEY = "condition";
    private static final String VALUE_KEY = "value";
    private static final String STATUS_KEY = "status";
    private static final String OTHER_CONDITION = "other";
    private static final String EQUALS_CONDITION = "equals";
    private static final String NOT_EQUALS_CONDITION = "not-equals";
    private static final String INCLUDE_CONDITION = "include";
    private static final String REGEX_CONDITION = "regex";
    private static final String PASS_STATUS = "pass";
    private static final String FAIL_STATUS = "fail";
    private static final String END_STATUS = "end";
    private static final String ZERO_VALUE = "0";
    private static final String NEW_LINE = "\n";
    private static final int MAX_LOG_LINES = 10_000;

    private final StringRedisTemplate redisTemplate;
    private final SseService sseService;
    private final TaskExecutionMapper taskExecutionMapper;
    private final TaskService taskService;
    private final MachineInfoMapper machineInfoMapper;
    private final TaskMapper taskMapper;
    private final DispatchStateStore dispatchStateStore;

    public TaskRpcServiceImpl(StringRedisTemplate redisTemplate,
                            SseService sseService,
                            TaskExecutionMapper taskExecutionMapper,
                            TaskService taskService,
                            MachineInfoMapper machineInfoMapper,
                            TaskMapper taskMapper,
                            DispatchStateStore dispatchStateStore) {
        this.redisTemplate = redisTemplate;
        this.sseService = sseService;
        this.taskExecutionMapper = taskExecutionMapper;
        this.taskService = taskService;
        this.machineInfoMapper = machineInfoMapper;
        this.taskMapper = taskMapper;
        this.dispatchStateStore = dispatchStateStore;
    }

    public static StatusResult getStatus(String input, String conditionConfig) {
        if (StringUtils.isBlank(input) || StringUtils.isBlank(conditionConfig)) {
            return new StatusResult(FAIL_STATUS, DEFAULT_OTHER_MESSAGE);
        }

        try {
            List<JSONObject> conditions = JSON.parseArray(conditionConfig, JSONObject.class);
            if (conditions == null || conditions.isEmpty()) {
                return getDefaultStatus(input);
            }

            return processConditions(input, conditions);
        } catch (Exception e) {
            logger.error("Failed to parse condition config: {}", conditionConfig, e);
            throw new RuntimeException("Failed to parse condition config", e);
        }
    }

    private static StatusResult processConditions(String input, List<JSONObject> conditions) {
        String otherStatus = null;

        for (JSONObject condition : conditions) {
            String conditionType = condition.getString(CONDITION_KEY);
            String value = condition.getString(VALUE_KEY);

            if (OTHER_CONDITION.equals(conditionType)) {
                otherStatus = condition.getString(STATUS_KEY);
                continue;
            }

            if (conditionType == null || value == null) {
                continue;
            }

            StatusResult result = evaluateCondition(input, condition, conditionType, value);
            if (result != null) {
                return result;
            }
        }

        return Optional.ofNullable(otherStatus)
                .map(status -> new StatusResult(status, DEFAULT_OTHER_MESSAGE))
                .orElseGet(() -> getDefaultStatus(input));
    }

    private static StatusResult evaluateCondition(String input, JSONObject condition, 
                                                String conditionType, String value) {
        String status = condition.getString(STATUS_KEY);
        String message = status + "：Input matches '" + conditionType + "' condition. Value: " + value;

        switch (conditionType) {
            case EQUALS_CONDITION:
                return input.equals(value) ? new StatusResult(status, message) : null;
            case NOT_EQUALS_CONDITION:
                return !input.equals(value) ? new StatusResult(status, message) : null;
            case INCLUDE_CONDITION:
                return input.contains(value) ? new StatusResult(status, message) : null;
            case REGEX_CONDITION:
                return Pattern.compile(value).matcher(input).find() ? 
                       new StatusResult(status, message) : null;
            default:
                return null;
        }
    }

    private static StatusResult getDefaultStatus(String input) {
        return new StatusResult(
            Objects.equals(input, ZERO_VALUE) ? PASS_STATUS : FAIL_STATUS,
            DEFAULT_OTHER_MESSAGE
        );
    }

    @Override
    public Boolean outputExecutionProcess(TaskExecuteInfo taskExecuteInfo) {
        if (taskExecuteInfo == null) {
            logger.warn("[Task] 收到空的 TaskExecuteInfo");
            return Boolean.FALSE;
        }

        try {
            if (!isAcceptedTaskEvent(taskExecuteInfo)) {
                logger.warn("[Task] 忽略过期执行事件: taskId={}, executeId={}",
                        taskExecuteInfo.getTaskId(), taskExecuteInfo.getExecuteId());
                return Boolean.FALSE;
            }
            String uuid = UUID.randomUUID().toString();

            processOutputLine(taskExecuteInfo, uuid);

            if (taskExecuteInfo.getFirst()) {
                handleFirstExecution(taskExecuteInfo);
            }

            if (taskExecuteInfo.getFinished()) {
                handleFinishedExecution(taskExecuteInfo);
            }

            return Boolean.TRUE;
        } catch (Exception e) {
            logger.error("[Task] 处理任务执行过程异常: taskId={}, executeId={}, ipAddress={}",
                        taskExecuteInfo.getTaskId(), taskExecuteInfo.getExecuteId(),
                        taskExecuteInfo.getIpAddress(), e);
            return Boolean.FALSE;
        }
    }

    private boolean isAcceptedTaskEvent(TaskExecuteInfo taskExecuteInfo) {
        DispatchStateRegistry.DispatchState state =
                dispatchStateStore.getDispatchByExecuteId(taskExecuteInfo.getExecuteId());
        if (StringUtils.isNotBlank(taskExecuteInfo.getDispatchToken())) {
            return state != null && Objects.equals(state.getDispatchToken(), taskExecuteInfo.getDispatchToken());
        }
        return state != null && StringUtils.isBlank(state.getDispatchToken());
    }

    private void processOutputLine(TaskExecuteInfo taskExecuteInfo, String uuid) {
        if (StringUtils.isBlank(taskExecuteInfo.getOutLine())) {
            return;
        }

        String outputWithId = taskExecuteInfo.getOutLine() + ID_NO_SEPARATOR + uuid;

        // 发送SSE消息
        if (!taskExecuteInfo.getFinished()) {
            sseService.send(
                SseTagEnum.EXECUTE_INFO.getCode(),
                String.valueOf(taskExecuteInfo.getExecuteId()),
                outputWithId
            );
        }

        // 存储到Redis
        if (!taskExecuteInfo.getFirst()) {
            redisTemplate.opsForList().rightPush(
                String.valueOf(taskExecuteInfo.getExecuteId()),
                outputWithId
            );
        }
    }

    private void handleFirstExecution(TaskExecuteInfo taskExecuteInfo) {
        logger.info("[Task] 任务开始执行: taskId={}, executeId={}, machineTag={}, command={}",
                   taskExecuteInfo.getTaskId(), taskExecuteInfo.getExecuteId(),
                   taskExecuteInfo.getIpAddress(), taskExecuteInfo.getOutLine());

        TaskExecutionEntity taskExecution = new TaskExecutionEntity();
        taskExecution.setId(taskExecuteInfo.getExecuteId());
        taskExecution.setStartTime(new Date());
        taskExecution.setIpAddress(taskExecuteInfo.getIpAddress());
        taskExecution.setStatus(
            taskExecuteInfo.getSuccess() ? CommonConstant.SUCCESS : CommonConstant.FAIL
        );
        taskExecution.setExecuteStatus(CommonConstant.RUNNING);

        taskExecutionMapper.updateById(taskExecution);
        taskService.updateMainTask(taskExecuteInfo.getTaskId());
        MachineLock.updateLockTime(taskExecuteInfo.getIpAddress(), System.currentTimeMillis());
    }

    private void handleFinishedExecution(TaskExecuteInfo taskExecuteInfo) {
        TaskEntity taskEntity = taskMapper.findById(taskExecuteInfo.getTaskId());
        List<String> logs = getLogsFromRedis(taskExecuteInfo.getExecuteId());

        TaskExecutionEntity taskExecution = buildFinishedTaskExecution(
            taskExecuteInfo, taskEntity, logs
        );

        updateTaskExecution(taskExecution);

        // 任务完成时，通过 SSE 发送完整日志（确保前端能收到）
        if (!logs.isEmpty()) {
            String fullLogs = String.join("\r\n", logs) + "\r\n[finished]";
            sseService.send(
                SseTagEnum.EXECUTE_INFO.getCode(),
                String.valueOf(taskExecuteInfo.getExecuteId()),
                fullLogs
            );
        } else {
            // 即使没有日志，也发送完成标记
            sseService.send(
                SseTagEnum.EXECUTE_INFO.getCode(),
                String.valueOf(taskExecuteInfo.getExecuteId()),
                "[finished] " + (taskExecuteInfo.getResult() != null ? taskExecuteInfo.getResult() : "")
            );
        }

        cleanupResources(taskExecuteInfo);

        // 计算执行耗时
        String duration = formatDuration(taskExecution.getExecuteTime());
        logger.info("[Task] 任务执行完成: taskId={}, executeId={}, machineTag={}, status={}, result={}, duration={}, logLines={}",
                   taskExecuteInfo.getTaskId(), taskExecuteInfo.getExecuteId(),
                   taskExecuteInfo.getIpAddress(), taskExecution.getStatus(),
                   taskExecuteInfo.getResult(), duration, logs.size());
    }

    private static String formatDuration(int millis) {
        if (millis < 1000) {
            return millis + "ms";
        } else if (millis < 60000) {
            return String.format("%.1fs", millis / 1000.0);
        } else {
            int minutes = millis / 60000;
            int seconds = (millis % 60000) / 1000;
            return minutes + "m" + seconds + "s";
        }
    }

    private List<String> getLogsFromRedis(Long executeId) {
        List<String> logs = redisTemplate.opsForList().range(
            String.valueOf(executeId),
            -MAX_LOG_LINES,
            -1
        );
        return logs != null ? logs.stream()
                .map(item -> item.split(ID_NO_SEPARATOR)[0])
                .toList() : List.of();
    }

    private TaskExecutionEntity buildFinishedTaskExecution(TaskExecuteInfo taskExecuteInfo, 
                                                          TaskEntity taskEntity, 
                                                          List<String> logs) {
        TaskExecutionEntity taskExecution = taskExecutionMapper.findById(taskExecuteInfo.getExecuteId());
        taskExecution.setId(taskExecuteInfo.getExecuteId());
        taskExecution.setLogs(String.join(NEW_LINE, keepLastLogLines(logs)));
        taskExecution.setEndTime(new Date());
        taskExecution.setResult(taskExecuteInfo.getResult());

        // 计算执行时间
        long duration = Duration.between(
            taskExecution.getStartTime().toInstant(), 
            taskExecution.getEndTime().toInstant()
        ).toMillis();
        taskExecution.setExecuteTime((int) duration);
        taskExecution.setExecuteStatus(END_STATUS);

        if (Boolean.TRUE.equals(taskExecuteInfo.getCanceled())) {
            taskExecution.setStatus(CommonConstant.CANCEL);
        } else {
            // 处理状态判断
            processTaskStatus(taskExecuteInfo, taskEntity, taskExecution);
        }

        return taskExecution;
    }

    private void processTaskStatus(TaskExecuteInfo taskExecuteInfo, 
                                 TaskEntity taskEntity, 
                                 TaskExecutionEntity taskExecution) {
        if (shouldApplyConditionConfig(taskExecuteInfo, taskEntity, taskExecution)) {
            StatusResult status = getStatus(
                taskExecution.getResult(),
                taskEntity.getConditionConfig()
            );
            taskExecution.setStatus(status.getStatus());
            
            sseService.send(
                SseTagEnum.EXECUTE_INFO.getCode(),
                String.valueOf(taskExecuteInfo.getExecuteId()),
                status.getMessage()
            );
            
            taskExecution.setLogs(taskExecution.getLogs() + NEW_LINE + status.getMessage());
        } else {
            taskExecution.setStatus(
                taskExecuteInfo.getSuccess() ? CommonConstant.SUCCESS : CommonConstant.FAIL
            );
        }
    }

    private boolean shouldApplyConditionConfig(TaskExecuteInfo taskExecuteInfo, 
                                             TaskEntity taskEntity, 
                                             TaskExecutionEntity taskExecution) {
        return StringUtils.isNotBlank(taskEntity.getConditionConfig())
                && StringUtils.isNotBlank(taskExecution.getResult())
                && taskExecuteInfo.getSuccess();
    }

    private void updateTaskExecution(TaskExecutionEntity taskExecution) {
        taskExecution.setLogs(keepLastLogLines(taskExecution.getLogs()));
        taskExecutionMapper.updateById(taskExecution);
    }

    private static List<String> keepLastLogLines(List<String> logs) {
        if (logs == null || logs.size() <= MAX_LOG_LINES) {
            return logs == null ? List.of() : logs;
        }
        return logs.subList(logs.size() - MAX_LOG_LINES, logs.size());
    }

    private static String keepLastLogLines(String logs) {
        if (StringUtils.isBlank(logs)) {
            return logs;
        }

        int newLineCount = 0;
        for (int i = logs.length() - 1; i >= 0; i--) {
            if (logs.charAt(i) == '\n') {
                newLineCount++;
                if (newLineCount >= MAX_LOG_LINES) {
                    return logs.substring(i + 1);
                }
            }
        }
        return logs;
    }

    private void cleanupResources(TaskExecuteInfo taskExecuteInfo) {
        DispatchStateRegistry.DispatchState dispatchState =
                dispatchStateStore.getDispatchByExecuteId(taskExecuteInfo.getExecuteId());
        String machineTag = dispatchState != null
                ? dispatchState.getMachineTag()
                : taskExecuteInfo.getIpAddress();
        // 删除Redis中的日志
        redisTemplate.delete(String.valueOf(taskExecuteInfo.getExecuteId()));
        
        // 更新主任务
        taskService.updateMainTask(taskExecuteInfo.getTaskId());
        
        // 释放机器资源
        if (dispatchState != null) {
            dispatchStateStore.clearIfCurrent(
                    dispatchState.getMachineTag(),
                    dispatchState.getTaskId(),
                    dispatchState.getExecuteId(),
                    dispatchState.getDispatchToken()
            );
            taskService.trySchedule(dispatchState.getMachineTag());
        } else {
            dispatchStateStore.releaseMachine(machineTag, taskExecuteInfo.getTaskId());
        }
    }
}
