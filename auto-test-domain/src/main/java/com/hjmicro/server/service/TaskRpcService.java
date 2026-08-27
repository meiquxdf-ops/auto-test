package com.hjmicro.server.service;


import com.hjmicro.ServiceInterface;
import com.hjmicro.domain.dto.TaskExecuteInfo;

public interface TaskRpcService extends ServiceInterface {

        Boolean outputExecutionProcess(TaskExecuteInfo taskExecuteInfo);

}
