package com.atest.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.atest.common.ApiException;
import com.atest.common.DisplayTags;
import com.atest.config.AtestProperties;
import com.atest.domain.AgentEntity;
import com.atest.domain.ExecutionStatus;
import com.atest.repo.AgentRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.sse.AgentSseService;
import com.atest.tcp.AgentRegistry;
import com.atest.web.dto.AgentView;
import com.atest.web.dto.PatchAgentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AgentService {

    private final AtestProperties props;
    private final AgentRepository agentRepository;
    private final TaskExecutionRepository executionRepository;
    private final AgentRegistry registry;
    private final DispatchService dispatchService;
    private final EventService eventService;
    private final AgentSseService agentSse;
    private final ViewMapper viewMapper;

    public AgentService(AtestProperties props,
                        AgentRepository agentRepository,
                        TaskExecutionRepository executionRepository,
                        AgentRegistry registry,
                        DispatchService dispatchService,
                        EventService eventService,
                        AgentSseService agentSse,
                        ViewMapper viewMapper) {
        this.props = props;
        this.agentRepository = agentRepository;
        this.executionRepository = executionRepository;
        this.registry = registry;
        this.dispatchService = dispatchService;
        this.eventService = eventService;
        this.agentSse = agentSse;
        this.viewMapper = viewMapper;
    }

    @Transactional(readOnly = true)
    public List<AgentView> list(String status, String keyword) {
        Map<String, Integer> active = viewMapper.activeCounts();
        return agentRepository.findAllByOrderByDisplayTagAsc().stream()
                .map(a -> viewMapper.toAgentView(a, active.getOrDefault(a.getAgentId(), 0)))
                .filter(v -> status == null || status.isBlank() || status.equalsIgnoreCase(v.status()))
                .filter(v -> keyword == null || keyword.isBlank()
                        || v.agentId().contains(keyword)
                        || (v.displayTag() != null && v.displayTag().contains(keyword)))
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentView get(String agentId) {
        return viewMapper.toAgentView(require(agentId));
    }

    public AgentEntity require(String agentId) {
        return agentRepository.findById(agentId)
                .or(() -> agentRepository.findByDisplayTag(agentId))
                .orElseThrow(() -> ApiException.notFound("agent 不存在: " + agentId));
    }

    /** Rename any time; concurrency only while the machine is idle. */
    @Transactional
    public AgentView patch(String agentId, PatchAgentRequest request) {
        AgentEntity agent = require(agentId);
        boolean changed = false;
        StringBuilder detail = new StringBuilder();

        String tag = request.resolvedTag();
        if (tag != null && !tag.equals(agent.getDisplayTag())) {
            tag = DisplayTags.requireValidHttp(tag);
            Optional<AgentEntity> owner = agentRepository.findByDisplayTag(tag);
            if (owner.isPresent() && !owner.get().getAgentId().equals(agent.getAgentId())) {
                throw ApiException.conflict("displayTag 重名: " + tag);
            }
            detail.append("displayTag ").append(agent.getDisplayTag()).append(" -> ").append(tag).append("; ");
            agent.setDisplayTag(tag);
            changed = true;
        }

        Integer concurrency = request.getConcurrency();
        if (concurrency != null && concurrency != agent.getConcurrency()) {
            int max = props.getConcurrency().getMaxValue();
            if (concurrency < 1 || concurrency > max) {
                throw ApiException.badRequest("concurrency 必须在 1.." + max + " 之间");
            }
            long active = executionRepository.countByAgentIdAndStatusIn(agent.getAgentId(),
                    List.of(ExecutionStatus.DISPATCHING, ExecutionStatus.RUNNING));
            if (active > 0) {
                throw ApiException.conflict("agent 非空闲（" + active + " 条执行中），不能改并发");
            }
            detail.append("concurrency ").append(agent.getConcurrency()).append(" -> ").append(concurrency);
            agent.setConcurrency(concurrency);
            changed = true;
        }

        if (changed) {
            agent.setUpdatedAt(Instant.now());
            agentRepository.save(agent);
            eventService.recordAgent(EventService.T_AGENT_PATCH, agent.getAgentId(), detail.toString());
            agentSse.publishAgent(agent);
        }
        return viewMapper.toAgentView(agent);
    }

    public Map<String, Object> restart(String agentId) {
        AgentEntity agent = require(agentId);
        boolean sent = dispatchService.sendRestart(agent.getAgentId());
        if (!sent) {
            throw ApiException.conflict("agent 当前离线，无法重启");
        }
        return Map.of("agentId", agent.getAgentId(), "restart", true);
    }

    public Map<String, Object> stop(String agentId) {
        AgentEntity agent = require(agentId);
        boolean sent = dispatchService.sendStop(agent.getAgentId(), "operator stop");
        if (!sent) {
            throw ApiException.conflict("agent 当前离线，无法停止");
        }
        return Map.of("agentId", agent.getAgentId(), "stop", true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        List<AgentView> agents = list(null, null);
        long online = agents.stream().filter(AgentView::online).count();
        long busy = agents.stream().filter(a -> a.activeCount() > 0).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", agents.size());
        out.put("online", online);
        out.put("offline", agents.size() - online);
        out.put("busy", busy);
        out.put("connections", registry.size());
        return out;
    }
}
