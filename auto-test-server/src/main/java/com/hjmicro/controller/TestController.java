package com.hjmicro.controller;

import com.hjmicro.agent.service.TaskHandlerService;
import com.hjmicro.domain.dto.TaskExecuteDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/test")
@RestController
public class TestController {

    @Autowired
    public TaskHandlerService taskHandlerService;

//        @RequestMapping("/hello")
//        public Boolean hello() {
//            TaskExecuteDTO taskExecuteDTO = new TaskExecuteDTO();
//            taskExecuteDTO.setExecuteId("123");
//            taskExecuteDTO.setTaskId("123");
//            taskExecuteDTO.setExecutableFilePath("bash /Users/eason/a.sh");
//            return taskHandlerService.doTask(taskExecuteDTO);
//        }







}
