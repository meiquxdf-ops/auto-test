package com.atest.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.atest.domain.AgentEventEntity;
import com.atest.domain.DispatchEventEntity;
import com.atest.repo.AgentEventRepository;
import com.atest.repo.DispatchEventRepository;
import com.atest.web.dto.TimelineItemView;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Merged agent + server timeline, newest first. */
@Service
public class TimelineService {

    private final AgentEventRepository agentEventRepository;
    private final DispatchEventRepository dispatchEventRepository;
    private final ViewMapper viewMapper;

    public TimelineService(AgentEventRepository agentEventRepository,
                           DispatchEventRepository dispatchEventRepository,
                           ViewMapper viewMapper) {
        this.agentEventRepository = agentEventRepository;
        this.dispatchEventRepository = dispatchEventRepository;
        this.viewMapper = viewMapper;
    }

    @Transactional(readOnly = true)
    public List<TimelineItemView> query(String agentId, String executeId, int limit) {
        int size = limit <= 0 ? 200 : Math.min(limit, 1000);
        Pageable pageable = PageRequest.of(0, size);
        List<AgentEventEntity> agentEvents;
        List<DispatchEventEntity> serverEvents;

        boolean hasAgent = agentId != null && !agentId.isBlank();
        boolean hasExec = executeId != null && !executeId.isBlank();
        if (hasAgent && hasExec) {
            agentEvents = agentEventRepository.findByAgentIdAndExecuteIdOrderByIdDesc(agentId, executeId, pageable);
            serverEvents = dispatchEventRepository.findByAgentIdAndExecuteIdOrderByIdDesc(agentId, executeId, pageable);
        } else if (hasExec) {
            agentEvents = agentEventRepository.findByExecuteIdOrderByIdDesc(executeId, pageable);
            serverEvents = dispatchEventRepository.findByExecuteIdOrderByIdDesc(executeId, pageable);
        } else if (hasAgent) {
            agentEvents = agentEventRepository.findByAgentIdOrderByIdDesc(agentId, pageable);
            serverEvents = dispatchEventRepository.findByAgentIdOrderByIdDesc(agentId, pageable);
        } else {
            agentEvents = agentEventRepository.findAllByOrderByIdDesc(pageable);
            serverEvents = dispatchEventRepository.findAllByOrderByIdDesc(pageable);
        }

        List<TimelineItemView> items = new ArrayList<>(agentEvents.size() + serverEvents.size());
        agentEvents.forEach(e -> items.add(viewMapper.toTimelineItem(e)));
        serverEvents.forEach(e -> items.add(viewMapper.toTimelineItem(e)));
        items.sort(Comparator.comparing(TimelineItemView::ts,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return items.size() > size ? items.subList(0, size) : items;
    }
}
