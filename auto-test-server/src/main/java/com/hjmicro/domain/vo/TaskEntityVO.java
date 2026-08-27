package com.hjmicro.domain.vo;

import com.hjmicro.fluent.entity.MachineInfoEntity;
import com.hjmicro.fluent.entity.TaskEntity;
import com.hjmicro.fluent.entity.TaskExecutionEntity;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TaskEntityVO extends TaskEntity {

    /**
     * 机器执行状态
     */
    List<TaskExecutionEntity> machineExecuteStatus;


    /**
     * 預計結束時間
     */
    private String expectEndTime;

    MachineInfoEntity machineInfoEntity;


}

