package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.atest.common.Json;
import com.atest.domain.AgentEntity;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.AgentRepository;
import com.atest.repo.TaskExecutionRepository;
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

/**
 * In-place rerun mints a fresh wire executeId for the reset execution. The id returned on the
 * TaskView (and recorded in the event log) must be the id actually persisted: it is the handle
 * clients use for /api/executions/{id}, the log stream and the agent fin path. Regression test
 * for the executeId column silently dropping the update (updatable=false), which left the OLD
 * wire id in the store while the response advertised a phantom id that 404s everywhere.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-rerun-inplace;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class RerunInPlaceExecuteIdTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private ExecutionService executionService;
    @Autowired
    private TaskExecutionRepository executionRepository;
    @Autowired
    private AgentRepository agentRepository;

    @BeforeEach
    void seedAgent() {
        if (agentRepository.findById("rerun-agent-a").isEmpty()) {
            AgentEntity agent = new AgentEntity();
            agent.setAgentId("rerun-agent-a");
            agent.setDisplayTag("rerun-agent-a");
            agent.setCreatedAt(Instant.now());
            agent.setUpdatedAt(Instant.now());
            agentRepository.save(agent);
        }
    }

    @Test
    void rerunInPlacePersistsTheAdvertisedExecuteId() {
        CreateTaskRequest create = new CreateTaskRequest();
        create.setCommand("echo rerun-inplace");
        create.setOperator("junit");
        create.setTargets(List.of((JsonNode) Json.mapper().getNodeFactory().textNode("rerun-agent-a")));
        TaskView task = taskService.create(create);

        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        String oldExecuteId = exec.getExecuteId();
        executionService.finish(exec, ExecutionStatus.PASS, "junit", null);

        RerunRequest rerun = new RerunRequest();
        rerun.setMode("inplace");
        TaskView view = taskService.rerun(task.id(), rerun);

        assertThat(view.id()).isEqualTo(task.id());
        String advertised = view.executions().get(0).executeId();
        // every attempt gets a fresh wire identity, never a reuse of the finished attempt's id
        assertThat(advertised).isNotEqualTo(oldExecuteId);

        TaskExecutionEntity reloaded = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.PENDING);
        assertThat(reloaded.getAttempt()).isEqualTo(2);
        // the identity handed to the caller is the identity in the store…
        assertThat(reloaded.getExecuteId()).isEqualTo(advertised);
        // …so the detail / log / fin lookup path resolves it, and the old id is gone
        assertThat(executionRepository.findByExecuteId(advertised)).isPresent();
        assertThat(executionRepository.findByExecuteId(oldExecuteId)).isEmpty();
    }
}
