package com.hjmicro.controller;

import com.alibaba.fastjson.JSONObject;
import com.hjmicro.domain.Result;
import com.hjmicro.domain.SseTagEnum;
import com.hjmicro.domain.request.MachineListRequest;
import com.hjmicro.domain.request.RepeatTaskRequest;
import com.hjmicro.domain.vo.TaskEntityVO;
import com.hjmicro.fluent.entity.TaskEntity;
import com.hjmicro.service.SseService;
import com.hjmicro.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RequestMapping("/sseEmitter")
@RestController
@CrossOrigin(originPatterns = "*")
public class TaskController {

    @Autowired
    private SseService service;


    @Autowired
    private TaskService taskService;


    /**
     * 获取任务列表
     * @return
     */
//    @GetMapping("/task/list")
//    public SseEmitter getTaskList() {
//        SseEmitter sseEmitter = service.registerConcentrator(SseTagEnum.TASK_LIST.getCode(), "all");
//        //初始化过程中的日志
//        List<TaskEntityVO> taskEntities = taskService.listTask();
//        try {
//            service.send(SseTagEnum.TASK_LIST.getCode(), "all", taskEntities);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        return sseEmitter;
//    }


    /**
     * 获取任务列表
     * @return
     */
    @GetMapping("/task/list")
    public SseEmitter getTaskList(String requestId) {
        SseEmitter sseEmitter;
        if (StringUtils.isEmpty(requestId)){
             sseEmitter = service.registerConcentrator(SseTagEnum.TASK_LIST.getCode(), "all");
//            //初始化过程中的日志
//            List<TaskEntityVO> taskEntities = taskService.listTask();
//            try {
//                service.send(SseTagEnum.TASK_LIST.getCode(), "all", taskEntities);
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
        }else{
             sseEmitter = service.registerConcentrator(SseTagEnum.TASK_LIST.getCode(), requestId);
            //初始化过程中的日志
            List<TaskEntityVO> taskEntities = taskService.listTaskByRequestId(requestId);
            try {
                service.send(SseTagEnum.TASK_LIST.getCode(), requestId, taskEntities);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return sseEmitter;
    }


    @GetMapping("/task/{executeId}/logs")
    public List<String> getTaskLogs(@PathVariable("executeId") String executeId, String normalEnd) {
        return taskService.getLogLinesByExecuteId(executeId, normalEnd);
    }

    @GetMapping(value = "/connect/{executeId}", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter connect(@PathVariable String executeId, String normalEnd,
                              @RequestParam(defaultValue = "false") Boolean skipInit) throws IOException {
        SseEmitter sseEmitter = service.registerConcentrator(SseTagEnum.EXECUTE_INFO.getCode(), executeId);
        if (!Boolean.TRUE.equals(skipInit)) {
            //初始化过程中的日志
            taskService.initLogByExecuteId(executeId,normalEnd);
        }
        return sseEmitter;
    }

    @RequestMapping("/doTask")
    public Result<Long> doTask(@RequestBody JSONObject request) {
        try {
            return Result.of(taskService.doTask(request));
        }catch (Exception e){
            e.printStackTrace();
            return Result.error(e.getMessage());
        }
    }


    @RequestMapping("/repeat")
    public Result<Boolean> repeat(@RequestBody RepeatTaskRequest request) {
        try {
            return Result.of(taskService.repeatTask(request));
        }catch (Exception e){
            e.printStackTrace();
            return Result.error(e.getMessage());
        }
    }

    @RequestMapping("/batchRepeat")
    public Result<Boolean> batchRepeat(@RequestBody JSONObject request) {
        try {
            return Result.of(taskService.batchRepeat(request.getJSONArray("ids").toJavaList(Long.class)));
        }catch (Exception e){
            e.printStackTrace();
            return Result.error(e.getMessage());
        }
    }


    @RequestMapping("/cancelTask")
    public Result<Boolean> cancelTask(Long id) {
        try {
            return Result.of(taskService.cancelTask(id));
        }catch (Exception e){
            e.printStackTrace();
            return Result.error(e.getMessage());
        }
    }

    @RequestMapping("/stopRunningTask")
    public Result<Boolean> stopRunningTask(Long id) {
        try {
            return Result.of(taskService.stopRunningTask(id));
        }catch (Exception e){
            e.printStackTrace();
            return Result.error(e.getMessage());
        }
    }


    @RequestMapping("/getTaskDetail")
    public Result<TaskEntityVO> getTaskDetail(String requestId) {
        try {
            return Result.of(taskService.getTaskDetail(requestId));
        }catch (Exception e){
            e.printStackTrace();
            return Result.error(e.getMessage());
        }
    }

}
