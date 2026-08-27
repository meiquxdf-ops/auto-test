package com.atest.web;

import java.util.Map;

import com.atest.service.TaskService;
import com.atest.web.dto.CreateTaskRequest;
import com.atest.web.dto.RerunRequest;
import com.atest.web.dto.ReorderRequest;
import com.atest.web.dto.TaskView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public TaskView create(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.create(request);
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(defaultValue = "true") boolean includeExecutions) {
        return taskService.list(status, page, size, includeExecutions);
    }

    @GetMapping("/{id}")
    public TaskView detail(@PathVariable Long id) {
        return taskService.detail(id);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id,
                                      @RequestParam(required = false) String operator) {
        return taskService.cancel(id, operator);
    }

    @PostMapping("/{id}/rerun")
    public TaskView rerun(@PathVariable Long id, @RequestBody(required = false) RerunRequest request) {
        return taskService.rerun(id, request);
    }

    @PostMapping("/reorder")
    public Map<String, Object> reorder(@RequestBody ReorderRequest request) {
        return taskService.reorder(request.resolve());
    }
}
