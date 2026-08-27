package com.hjmicro.controller;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hjmicro.domain.Result;
import com.hjmicro.domain.dto.AgentEventDTO;
import com.hjmicro.domain.request.MachineListRequest;
import com.hjmicro.fluent.entity.MachineInfoEntity;
import com.hjmicro.fluent.entity.TaskExecutionEntity;
import com.hjmicro.fluent.mapper.TaskExecutionMapper;
import com.hjmicro.fluent.wrapper.TaskExecutionQuery;
import com.hjmicro.service.MachineService;
import com.hjmicro.service.TaskService;
import com.hjmicro.service.impl.rpc.AgentEventServiceImpl;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RequestMapping("/debug")
@RestController
@CrossOrigin(originPatterns = "*")
public class DebugController {

    @Autowired
    private MachineService machineService;

    @Autowired
    private TaskExecutionMapper taskExecutionMapper;

    @Autowired
    private TaskService taskService;

    @Autowired
    private AgentEventServiceImpl agentEventService;

    /**
     * 获取所有在线机器
     */
    @GetMapping("/machines")
    public Result<List<MachineInfoEntity>> getOnlineMachines() {
        try {
            MachineListRequest request = new MachineListRequest();
            request.setMachineInfoEntity(new MachineInfoEntity());
            com.hjmicro.domain.PageRequest pageRequest = new com.hjmicro.domain.PageRequest();
            pageRequest.setPageSize(1000);
            request.setRequest(pageRequest);

            List<MachineInfoEntity> machines = machineService.getMachines(request).getData();
            // 只返回在线的机器
            List<MachineInfoEntity> onlineMachines = machines.stream()
                    .filter(m -> "ONLINE".equals(m.getStatus()))
                    .toList();
            return Result.of(onlineMachines);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 发送调试命令
     */
    @PostMapping("/execute")
    public Result<DebugExecuteResponse> executeCommand(@RequestBody DebugExecuteRequest request) {
        try {
            JSONArray targetIps = new JSONArray();
            targetIps.add(request.getMachineTag());

            JSONObject taskRequest = new JSONObject();
            taskRequest.put("requestId", "DEBUG_" + UUID.randomUUID());
            taskRequest.put("taskName", "DEBUG_" + System.currentTimeMillis());
            taskRequest.put("taskDesc", "调试命令: " + request.getCommand());
            taskRequest.put("operator", "debug");
            taskRequest.put("executableFilePath", request.getCommand());
            taskRequest.put("conditionConfig", "");
            taskRequest.put("targetIps", targetIps);

            Long taskId = taskService.doTask(taskRequest);
            TaskExecutionEntity executionEntity = findLatestExecution(taskId);

            DebugExecuteResponse response = new DebugExecuteResponse();
            response.setTaskId(taskId);
            response.setExecuteId(executionEntity == null ? null : executionEntity.getId());
            response.setMessage(executionEntity == null ? "命令已进入队列" : "命令已下发");

            return Result.of(response);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private TaskExecutionEntity findLatestExecution(Long taskId) {
        if (taskId == null) {
            return null;
        }
        List<TaskExecutionEntity> executions = taskExecutionMapper.listEntity(
                new TaskExecutionQuery()
                        .where.taskId().eq(taskId)
                        .and.isDeleted().eq(0)
                        .end()
                        .orderBy.id().desc()
                        .end()
        );
        return executions.isEmpty() ? null : executions.get(0);
    }

    /**
     * 停止指定机器上的任务
     */
    @PostMapping("/stop")
    public Result<Boolean> stopTask(@RequestBody StopTaskRequest request) {
        try {
            return Result.of(taskService.clearAgentRunningTask(request.getMachineTag()));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 重启指定机器上的 Agent。Agent 进程会退出，必须由外部守护进程拉起。
     */
    @PostMapping("/agent/restart")
    public Result<Boolean> restartAgent(@RequestBody StopTaskRequest request) {
        try {
            return Result.of(taskService.restartAgent(request.getMachineTag()));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/agent/events")
    public Result<List<AgentEventDTO>> listAgentEvents(
            @RequestParam(required = false) String machineTag,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long executeId,
            @RequestParam(defaultValue = "200") int limit
    ) {
        try {
            return Result.of(agentEventService.listEvents(machineTag, requestId, taskId, executeId, limit));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @Data
    public static class DebugExecuteRequest {
        private String machineTag;
        private String command;
    }

    @Data
    public static class DebugExecuteResponse {
        private Long taskId;
        private Long executeId;
        private String message;
    }

    @Data
    public static class StopTaskRequest {
        private String machineTag;
    }
}
