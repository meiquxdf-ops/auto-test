package com.atest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.atest.config.AtestProperties;
import com.atest.domain.AgentEntity;
import com.atest.repo.TaskAttachmentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.tcp.AgentRegistry;
import com.atest.web.dto.AgentView;
import org.junit.jupiter.api.Test;

/**
 * The agent's heartbeat-reported running_count lags the store, so it used to contradict the
 * live fields on the same payload: runningCount=0 with idle=false, or runningCount=1 with
 * idle=true. runningCount is kept for API compat but must mirror the live activeCount.
 */
class ViewMapperRunningCountTest {

    private static AgentEntity agent(int staleRunningCount) {
        AgentEntity agent = new AgentEntity();
        agent.setAgentId("vm-agent");
        agent.setDisplayTag("vm-agent");
        agent.setRunningCount(staleRunningCount);
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        return agent;
    }

    private static ViewMapper mapper(long liveActiveCount) {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.isOnline("vm-agent")).thenReturn(true);
        TaskExecutionRepository repo = mock(TaskExecutionRepository.class);
        when(repo.countByAgentIdAndStatusIn(eq("vm-agent"), anyCollection())).thenReturn(liveActiveCount);
        return new ViewMapper(new AtestProperties(), registry, repo, mock(TaskAttachmentRepository.class));
    }

    @Test
    void staleHeartbeatZeroDoesNotHideLiveWork() {
        // heartbeat still says 0 but the store already has an active execution
        AgentView view = mapper(1).toAgentView(agent(0));
        assertThat(view.activeCount()).isEqualTo(1);
        assertThat(view.runningCount()).isEqualTo(1);
        assertThat(view.idle()).isFalse();
    }

    @Test
    void staleHeartbeatOneDoesNotContradictIdle() {
        // heartbeat still says 1 but the execution already finished in the store
        AgentView view = mapper(0).toAgentView(agent(1));
        assertThat(view.activeCount()).isZero();
        assertThat(view.runningCount()).isZero();
        assertThat(view.idle()).isTrue();
    }

    @Test
    void explicitActiveCountOverloadStaysConsistentToo() {
        AgentView view = mapper(0).toAgentView(agent(3), 2);
        assertThat(view.runningCount()).isEqualTo(2);
        assertThat(view.activeCount()).isEqualTo(2);
        assertThat(view.idle()).isFalse();
    }
}
