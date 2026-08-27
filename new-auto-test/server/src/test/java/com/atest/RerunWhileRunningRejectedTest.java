package com.atest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import com.atest.common.ApiException;
import com.atest.common.Json;
import com.atest.domain.AgentEntity;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.AgentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.service.ExecutionService;
import com.atest.service.TaskService;
import com.atest.web.dto.CreateTaskRequest;
import com.atest.web.dto.RerunRequest;
import com.atest.web.dto.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * Rerunning a task whose selected execution is still live (pending/dispatching/running) is a
 * 409 for BOTH modes. In-place always rejected this; mode=new used to happily clone a task
 * whose source was still running, doubling the load on the same agent and producing two
 * concurrent executions of the same command. The live execution must be left untouched.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-rerun-running;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class RerunWhileRunningRejectedTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private ExecutionService executionService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskExecutionRepository executionRepository;
    @Autowired
    private AgentRepository agentRepository;

    @BeforeEach
    void seedAgent() {
        if (agentRepository.findById("rerun-run-agent").isEmpty()) {
            AgentEntity agent = new AgentEntity();
            agent.setAgentId("rerun-run-agent");
            agent.setDisplayTag("rerun-run-agent");
            agent.setCreatedAt(Instant.now());
            agent.setUpdatedAt(Instant.now());
            agentRepository.save(agent);
        }
    }

    private TaskView createTask() {
        CreateTaskRequest create = new CreateTaskRequest();
        create.setCommand("echo rerun-while-running");
        create.setOperator("junit");
        create.setTargets(List.of((JsonNode) Json.mapper().getNodeFactory().textNode("rerun-run-agent")));
        return taskService.create(create);
    }

    private RerunRequest rerun(String mode) {
        RerunRequest request = new RerunRequest();
        request.setMode(mode);
        return request;
    }

    @Test
    void rerunAsNewTaskWhileRunningIs409AndLeavesTheExecutionAlone() {
        TaskView task = createTask();
        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        exec.setStatus(ExecutionStatus.RUNNING);
        exec.setStartedAt(Instant.now());
        executionRepository.save(exec);
        String executeId = exec.getExecuteId();
        long tasksBefore = taskRepository.count();

        assertThatThrownBy(() -> taskService.rerun(task.id(), rerun("new")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // no clone was persisted and the running execution is undisturbed
        assertThat(taskRepository.count()).isEqualTo(tasksBefore);
        TaskExecutionEntity reloaded = executionRepository.findByExecuteId(executeId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(reloaded.getAttempt()).isEqualTo(1);
    }

    @Test
    void rerunInPlaceWhileRunningStays409() {
        TaskView task = createTask();
        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        exec.setStatus(ExecutionStatus.RUNNING);
        executionRepository.save(exec);

        assertThatThrownBy(() -> taskService.rerun(task.id(), rerun("inplace")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void rerunAsNewTaskAfterFinishStillWorks() {
        TaskView task = createTask();
        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        executionService.finish(exec, ExecutionStatus.PASS, "junit", null);

        TaskView clone = taskService.rerun(task.id(), rerun("new"));
        assertThat(clone.id()).isNotEqualTo(task.id());
        assertThat(clone.rerunOf()).isEqualTo(task.id());
        assertThat(clone.executions()).hasSize(1);
    }
}
