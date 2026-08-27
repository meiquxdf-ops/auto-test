package com.atest.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.service.DispatchService;
import com.atest.service.ExecutionService;
import com.atest.service.LogService;
import com.atest.service.ViewMapper;
import com.atest.web.dto.ExecutionView;
import com.atest.web.dto.LogPageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private final ExecutionService executionService;
    private final DispatchService dispatchService;
    private final LogService logService;
    private final ViewMapper viewMapper;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;

    public ExecutionController(ExecutionService executionService,
                               DispatchService dispatchService,
                               LogService logService,
                               ViewMapper viewMapper,
                               TaskRepository taskRepository,
                               TaskExecutionRepository executionRepository) {
        this.executionService = executionService;
        this.dispatchService = dispatchService;
        this.logService = logService;
        this.viewMapper = viewMapper;
        this.taskRepository = taskRepository;
        this.executionRepository = executionRepository;
    }

    @GetMapping
    public List<ExecutionView> list(@RequestParam(required = false) Long taskId,
                                    @RequestParam(required = false) String agentId,
                                    @RequestParam(required = false) String status) {
        List<TaskExecutionEntity> executions;
        if (taskId != null) {
            executions = executionRepository.findByTaskIdOrderByIdAsc(taskId);
        } else if (agentId != null && !agentId.isBlank()) {
            executions = executionRepository.findByAgentIdAndStatusIn(agentId, List.of(ExecutionStatus.values()));
        } else {
            executions = executionRepository.findAllActive();
        }
        ExecutionStatus filter = ExecutionStatus.fromWire(status);
        return executions.stream()
                .filter(e -> filter == null || e.getStatus() == filter)
                .map(e -> viewMapper.toExecutionView(e, taskRepository.findById(e.getTaskId()).orElse(null)))
                .toList();
    }

    @GetMapping("/{id}")
    public ExecutionView get(@PathVariable String id) {
        TaskExecutionEntity exec = executionService.require(id);
        TaskEntity task = taskRepository.findById(exec.getTaskId()).orElse(null);
        return viewMapper.toExecutionView(exec, task);
    }

    @GetMapping("/{id}/logs")
    public LogPageView logs(@PathVariable String id,
                            @RequestParam(defaultValue = "0") int from,
                            @RequestParam(defaultValue = "1000") int limit) {
        TaskExecutionEntity exec = executionService.require(id);
        return logService.page(exec, from, limit);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id,
                                      @RequestParam(required = false) String operator) {
        TaskExecutionEntity exec = executionService.require(id);
        boolean applied = dispatchService.requestCancel(exec,
                "用户取消" + (operator == null ? "" : " by " + operator), true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executeId", exec.getExecuteId());
        result.put("applied", applied);
        result.put("status", executionService.require(id).getStatus().wire());
        return result;
    }
}
