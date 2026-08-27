package com.hjmicro.domain.request;

import com.hjmicro.fluent.entity.MachineInfoEntity;
import com.hjmicro.fluent.entity.TaskEntity;
import lombok.Data;

@Data
public class RepeatTaskRequest {

    private MachineInfoEntity machineInfoEntity;

    private TaskEntity task;

}
