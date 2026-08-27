package com.hjmicro.service;

import com.alibaba.fastjson.JSONObject;
import com.hjmicro.domain.request.MachineListRequest;
import com.hjmicro.domain.request.RepeatTaskRequest;
import com.hjmicro.domain.vo.TaskEntityVO;
import com.hjmicro.fluent.entity.TaskEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface TaskService {
    /**
     * 初始化任务
     */
    Long doTask(JSONObject request);


    Boolean updateMainTask(Long  task);

    List<TaskEntityVO>  listTaskByRequestId(String requestId);


    List<TaskEntityVO> listTask(String requestId);

    /**
     * 更新任务收口
     * @param taskEntity
     * @return
     */
    Boolean updateTaskById(TaskEntity taskEntity);

    /**
     * 新增任务收口
     * @param taskEntity
     * @return
     */
    TaskEntity addTask(TaskEntity taskEntity);

    void initLogByExecuteId(String executeId,String normalEnd);

    List<String> getLogLinesByExecuteId(String executeId, String normalEnd);

    TaskEntityVO getTaskDetail(String taskId);

    Boolean repeatTask(RepeatTaskRequest request);

    Long executeNextTask(String ipAddress);

    Long trySchedule(String machineTag);

    boolean cancelTask(Long id);

    boolean batchRepeat(List<Long> ids);

    void noticeFrontByIp(String ip);

    boolean stopRunningTask(Long id);

    boolean clearAgentRunningTask(String machineTag);

    boolean restartAgent(String machineTag);
}
