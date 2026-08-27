package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.atest.common.Json;
import com.atest.domain.AgentEntity;
import com.atest.domain.ExecutionStatus;
import com.atest.domain.TaskExecutionEntity;
import com.atest.domain.TaskStatus;
import com.atest.repo.AgentRepository;
import com.atest.repo.DispatchEventRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.repo.TaskRepository;
import com.atest.service.DispatchService;
import com.atest.service.EventService;
import com.atest.service.ExecutionService;
import com.atest.service.TaskService;
import com.atest.web.dto.CreateTaskRequest;
import com.atest.web.dto.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

/**
 * A cancel (or watchdog timeout) request races the fin that finalizes the same execution: the
 * canceling transaction loads the row while it is still running, the fin lands and commits a
 * terminal state, then the canceler flags cancelRequested through its stale snapshot. The flag
 * write must be a targeted conditional update — writing the whole stale row resurrects the
 * finished execution back to running (finishedAt nulled, lease restored), leaving a zombie the
 * lease reaper has to kill again ~45s later and a second terminal transition in the event log.
 *
 * <p>Reproduced live with 10 parallel POST /api/tasks/{id}/cancel on one running execution:
 * timeline showed canceled at t, cancel_sent events after t, the row back to running with
 * cancelRequested=true, then a second canceled ("取消后进程已消失") from the reaper 47s later.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-cancel-fin-race;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class CancelFinRaceTest {

    private static final String AGENT = "cancel-race-agent-a";

    @Autowired
    private TaskService taskService;
    @Autowired
    private ExecutionService executionService;
    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private TaskExecutionRepository executionRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private DispatchEventRepository dispatchEventRepository;

    @BeforeEach
    void seedAgent() {
        if (agentRepository.findById(AGENT).isEmpty()) {
            AgentEntity agent = new AgentEntity();
            agent.setAgentId(AGENT);
            agent.setDisplayTag(AGENT);
            agent.setCreatedAt(Instant.now());
            agent.setUpdatedAt(Instant.now());
            agentRepository.save(agent);
        }
    }

    private TaskExecutionEntity createRunningExecution(String command) {
        CreateTaskRequest create = new CreateTaskRequest();
        create.setCommand(command);
        create.setOperator("junit");
        create.setTargets(List.of((JsonNode) Json.mapper().getNodeFactory().textNode(AGENT)));
        TaskView task = taskService.create(create);

        TaskExecutionEntity exec = executionRepository.findByTaskIdOrderByIdAsc(task.id()).get(0);
        Instant now = Instant.now();
        exec.setStatus(ExecutionStatus.RUNNING);
        exec.setAcked(true);
        exec.setDispatchToken("junit-token");
        exec.setLeaseExpireAt(now.plusSeconds(60));
        exec.setDispatchedAt(now);
        exec.setStartedAt(now);
        exec.setUpdatedAt(now);
        return executionRepository.save(exec);
    }

    @Test
    void lateCancelRacerMustNotResurrectAFinalizedExecution() {
        TaskExecutionEntity running = createRunningExecution("echo cancel-fin-race");
        Long execId = running.getId();

        // the racing canceler loads its snapshot while the row is still running…
        TaskExecutionEntity staleSnapshot = executionRepository.findById(execId).orElseThrow();
        assertThat(staleSnapshot.getStatus()).isEqualTo(ExecutionStatus.RUNNING);

        // …then the agent's fin (triggered by an earlier cancel) finalizes the execution…
        TaskExecutionEntity fresh = executionRepository.findById(execId).orElseThrow();
        executionService.finish(fresh, ExecutionStatus.CANCELED, "用户取消", null);
        Instant finishedAt = executionRepository.findById(execId).orElseThrow().getFinishedAt();
        assertThat(finishedAt).isNotNull();

        // …and only now does the late canceler proceed with its stale running snapshot
        boolean sent = dispatchService.requestCancel(staleSnapshot, "用户取消 by 迟到的并发取消", true);
        assertThat(sent).as("nothing is left to cancel on a finalized execution").isFalse();

        TaskExecutionEntity after = executionRepository.findById(execId).orElseThrow();
        assertThat(after.getStatus())
                .as("a terminal execution must never flip back to running")
                .isEqualTo(ExecutionStatus.CANCELED);
        assertThat(after.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(after.getLeaseExpireAt()).as("no lease may be restored on a finished row").isNull();
        assertThat(taskRepository.findById(after.getTaskId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.CANCELED);
    }

    @Test
    void lateTimeoutWatchdogMustNotResurrectAPassedExecution() {
        TaskExecutionEntity running = createRunningExecution("echo timeout-fin-race");
        Long execId = running.getId();

        // watchdog scans and holds a running snapshot; the process exits normally in between
        TaskExecutionEntity staleSnapshot = executionRepository.findById(execId).orElseThrow();
        TaskExecutionEntity fresh = executionRepository.findById(execId).orElseThrow();
        executionService.finish(fresh, ExecutionStatus.PASS, "junit", null);
        Instant finishedAt = executionRepository.findById(execId).orElseThrow().getFinishedAt();

        boolean sent = dispatchService.requestCancel(staleSnapshot, "timeout after 1s", false);
        assertThat(sent).isFalse();

        TaskExecutionEntity after = executionRepository.findById(execId).orElseThrow();
        assertThat(after.getStatus())
                .as("a passed execution must not be revived or re-flagged by the watchdog")
                .isEqualTo(ExecutionStatus.PASS);
        assertThat(after.isTimeoutRequested()).isFalse();
        assertThat(after.getFinishedAt()).isEqualTo(finishedAt);

        // and no timeout event may be logged against the already-finished run
        List<String> types = dispatchEventRepository
                .findByExecuteIdOrderByIdDesc(after.getExecuteId(), PageRequest.of(0, 100))
                .stream().map(e -> e.getType()).toList();
        assertThat(types).doesNotContain(EventService.T_TIMEOUT);
    }
}
