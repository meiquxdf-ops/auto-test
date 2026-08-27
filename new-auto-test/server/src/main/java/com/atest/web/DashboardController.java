package com.atest.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.atest.config.AtestProperties;
import com.atest.domain.ExecutionStatus;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.service.AgentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Numbers for the overview page. */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final AgentService agentService;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final AtestProperties props;

    public DashboardController(AgentService agentService,
                               TaskRepository taskRepository,
                               TaskExecutionRepository executionRepository,
                               AtestProperties props) {
        this.agentService = agentService;
        this.taskRepository = taskRepository;
        this.executionRepository = executionRepository;
        this.props = props;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Long> executionsByStatus = new LinkedHashMap<>();
        for (ExecutionStatus status : ExecutionStatus.values()) {
            executionsByStatus.put(status.wire(), 0L);
        }
        for (Object[] row : executionRepository.countByStatusGrouped()) {
            executionsByStatus.put(((ExecutionStatus) row[0]).wire(), ((Number) row[1]).longValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agents", agentService.summary());
        out.put("executions", executionsByStatus);
        out.put("tasks", taskRepository.count());
        out.put("limits", Map.of(
                "concurrencyDefault", props.getConcurrency().getDefaultValue(),
                "concurrencyMax", props.getConcurrency().getMaxValue(),
                "logMaxBytesPerExecution", props.getLogs().getMaxBytesPerExecution()));
        return out;
    }
}
