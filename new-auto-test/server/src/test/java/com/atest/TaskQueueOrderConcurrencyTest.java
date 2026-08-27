package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.atest.common.Json;
import com.atest.domain.AgentEntity;
import com.atest.repo.AgentRepository;
import com.atest.service.TaskService;
import com.atest.web.dto.CreateTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * queueOrder is the queue position the dispatcher sorts by ({@code order by t.queueOrder asc})
 * and the manual reorder API rewrites, so every task must get a distinct value.
 *
 * Live repro (2026-08-27, five-agent burst test): 15 parallel POST /api/tasks produced
 * queueOrder 193 for tasks 287+288 and 196 for tasks 291+292. Root cause: persistTask
 * computed {@code maxQueueOrder() + 1} inside the creation transaction, so two concurrent
 * creates read the same max before either committed and both stored the same position.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-queue-order;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class TaskQueueOrderConcurrencyTest {

    private static final String AGENT = "queue-order-agent";

    @Autowired
    private TaskService taskService;
    @Autowired
    private AgentRepository agentRepository;

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

    @Test
    void concurrentCreatesAssignDistinctQueueOrders() throws Exception {
        int threads = 8;
        int rounds = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // one barrier per round so every round is a genuinely simultaneous burst
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Callable<List<Long>>> workers = new java.util.ArrayList<>();
            for (int w = 0; w < threads; w++) {
                final int worker = w;
                workers.add(() -> {
                    List<Long> orders = new java.util.ArrayList<>();
                    for (int r = 0; r < rounds; r++) {
                        barrier.await();
                        CreateTaskRequest request = new CreateTaskRequest();
                        request.setName("qo-" + worker + "-" + r);
                        request.setCommand("echo qo");
                        request.setOperator("junit");
                        request.setTargets(List.of(Json.mapper().getNodeFactory().textNode(AGENT)));
                        orders.add(taskService.create(request).queueOrder());
                    }
                    return orders;
                });
            }

            List<Long> all = new java.util.ArrayList<>();
            for (Future<List<Long>> f : pool.invokeAll(workers)) {
                all.addAll(f.get());
            }

            assertThat(all).hasSize(threads * rounds);
            Map<Long, Long> byValue = all.stream()
                    .collect(Collectors.groupingBy(o -> o, Collectors.counting()));
            Set<Long> duplicated = byValue.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            assertThat(duplicated)
                    .as("every created task must hold a distinct queue position, got %s twice or more", duplicated)
                    .isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }
}
