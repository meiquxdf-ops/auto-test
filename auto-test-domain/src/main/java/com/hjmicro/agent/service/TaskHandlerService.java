package com.hjmicro.agent.service;


import com.hjmicro.ClientService;
import com.hjmicro.ServiceInterface;
import com.hjmicro.domain.dto.TaskExecuteDTO;

import java.util.List;

@ClientService
public interface TaskHandlerService extends ServiceInterface {

    List<String> listPathFile();

    Boolean doTask(TaskExecuteDTO taskExecuteDTO);

    Boolean stopTask();

    Boolean cancelTask(Long executeId, String dispatchToken);

    Boolean restartAgent();
}
