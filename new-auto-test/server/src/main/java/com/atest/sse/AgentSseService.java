package com.atest.sse;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.atest.config.AtestProperties;
import com.atest.domain.AgentEntity;
import com.atest.repo.AgentRepository;
import com.atest.service.ViewMapper;
import com.atest.web.dto.AgentView;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** GET /api/sse/agents: one snapshot on connect, then patches. */
@Slf4j
@Service
public class AgentSseService {

    private final AtestProperties props;
    private final AgentRepository agentRepository;
    private final ViewMapper viewMapper;
    private final ExecutorService sseExecutor;
    private final ScheduledExecutorService heartbeatScheduler;
    private final Set<AsyncSseEmitter> subscribers = ConcurrentHashMap.newKeySet();

    public AgentSseService(AtestProperties props,
                           AgentRepository agentRepository,
                           ViewMapper viewMapper,
                           @Qualifier("sseExecutor") ExecutorService sseExecutor,
                           @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.props = props;
        this.agentRepository = agentRepository;
        this.viewMapper = viewMapper;
        this.sseExecutor = sseExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @PostConstruct
    void startHeartbeat() {
        long period = Math.max(1000, props.getSse().getHeartbeatMs());
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            for (AsyncSseEmitter emitter : subscribers) {
                if (emitter.isClosed()) {
                    subscribers.remove(emitter);
                } else {
                    emitter.comment("hb");
                }
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    public SseEmitter subscribe() {
        SseEmitter raw = new SseEmitter(props.getSse().getEmitterTimeoutMs() <= 0
                ? Long.MAX_VALUE : props.getSse().getEmitterTimeoutMs());
        AsyncSseEmitter emitter = new AsyncSseEmitter(raw, sseExecutor, props.getSse().getQueueCapacity());
        subscribers.add(emitter);
        raw.onCompletion(() -> subscribers.remove(emitter));
        raw.onTimeout(() -> subscribers.remove(emitter));
        raw.onError(t -> subscribers.remove(emitter));
        emitter.send("snapshot", Map.of("agents", snapshot(), "ts", System.currentTimeMillis()));
        return raw;
    }

    public List<AgentView> snapshot() {
        Map<String, Integer> active = viewMapper.activeCounts();
        List<AgentEntity> agents = agentRepository.findAllByOrderByDisplayTagAsc();
        return agents.stream()
                .map(a -> viewMapper.toAgentView(a, active.getOrDefault(a.getAgentId(), 0)))
                .toList();
    }

    public void publishPatch(AgentView agent) {
        if (agent == null || subscribers.isEmpty()) {
            return;
        }
        publish(List.of(agent));
    }

    public void publish(List<AgentView> agents) {
        if (agents.isEmpty() || subscribers.isEmpty()) {
            return;
        }
        Map<String, Object> payload = Map.of("agents", agents, "ts", System.currentTimeMillis());
        for (AsyncSseEmitter emitter : subscribers) {
            if (emitter.isClosed()) {
                subscribers.remove(emitter);
                continue;
            }
            emitter.send("patch", payload);
        }
    }

    public void publishAgent(AgentEntity agent) {
        if (agent == null || subscribers.isEmpty()) {
            return;
        }
        publishPatch(viewMapper.toAgentView(agent));
    }
}
