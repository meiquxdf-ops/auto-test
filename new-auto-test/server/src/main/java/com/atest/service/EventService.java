package com.atest.service;

import java.time.Instant;
import java.util.List;

import com.atest.common.Json;
import com.atest.domain.AgentEventEntity;
import com.atest.domain.DispatchEventEntity;
import com.atest.repo.AgentEventRepository;
import com.atest.repo.DispatchEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EventService {

    public static final String T_CREATED = "created";
    public static final String T_DISPATCHING = "dispatching";
    public static final String T_ACK = "ack";
    public static final String T_REJECTED = "rejected";
    public static final String T_RUNNING = "running";
    public static final String T_FINISHED = "finished";
    public static final String T_CANCEL_SENT = "cancel_sent";
    public static final String T_CANCELED = "canceled";
    public static final String T_TIMEOUT = "timeout";
    public static final String T_LEASE_EXPIRED = "lease_expired";
    public static final String T_DISCONNECTED = "disconnected";
    public static final String T_RECONNECTED = "reconnected";
    public static final String T_RECONCILE = "reconcile";
    public static final String T_AGENT_ONLINE = "agent_online";
    public static final String T_AGENT_OFFLINE = "agent_offline";
    public static final String T_AGENT_TAKEOVER = "agent_takeover";
    public static final String T_AGENT_DUP_SESSION = "agent_dup_session";
    public static final String T_AGENT_STOP = "agent_stop";
    public static final String T_AGENT_RESTART = "agent_restart";
    public static final String T_AGENT_PATCH = "agent_patch";
    public static final String T_CALLBACK = "callback";
    public static final String T_TASK_CREATED = "task_created";
    public static final String T_TASK_CANCELED = "task_canceled";
    public static final String T_TASK_RERUN = "task_rerun";
    public static final String T_TASK_REORDER = "task_reorder";

    private final DispatchEventRepository dispatchEventRepository;
    private final AgentEventRepository agentEventRepository;

    public EventService(DispatchEventRepository dispatchEventRepository,
                        AgentEventRepository agentEventRepository) {
        this.dispatchEventRepository = dispatchEventRepository;
        this.agentEventRepository = agentEventRepository;
    }

    public void record(String type, String agentId, String executeId, Long taskId, String detail) {
        try {
            DispatchEventEntity event = new DispatchEventEntity();
            event.setType(type);
            event.setAgentId(agentId);
            event.setExecuteId(executeId);
            event.setTaskId(taskId);
            event.setDetail(detail);
            event.setCreatedAt(Instant.now());
            dispatchEventRepository.save(event);
        } catch (Exception e) {
            log.warn("failed to persist dispatch event type={} agent={} exec={}", type, agentId, executeId, e);
        }
    }

    public void recordAgent(String type, String agentId, String detail) {
        record(type, agentId, null, null, detail);
    }

    /** (agentId, evtId) is unique, replays are dropped. */
    public List<String> ingestAgentEvents(String agentId, JsonNode eventsNode) {
        if (eventsNode == null || !eventsNode.isArray()) {
            return List.of();
        }
        Instant now = Instant.now();
        List<String> acked = new java.util.ArrayList<>();
        for (JsonNode node : eventsNode) {
            String evtId = Json.text(node, "evtId", "id", "eid");
            if (evtId == null || evtId.isBlank()) {
                continue;
            }
            acked.add(evtId);
            try {
                if (agentEventRepository.existsByAgentIdAndEvtId(agentId, evtId)) {
                    continue;
                }
                AgentEventEntity entity = new AgentEventEntity();
                entity.setAgentId(agentId);
                entity.setEvtId(evtId);
                String type = Json.text(node, "type", "t", "kind");
                entity.setType(type == null ? "unknown" : type);
                entity.setExecuteId(Json.text(node, "executeId", "execId", "eid2"));
                JsonNode msg = Json.first(node, "msg", "message", "detail", "data");
                entity.setMessage(msg == null ? null : (msg.isTextual() ? msg.asText() : msg.toString()));
                entity.setEventTime(readInstant(node));
                entity.setCreatedAt(now);
                agentEventRepository.save(entity);
            } catch (DataIntegrityViolationException dup) {
                log.debug("duplicate agent event agent={} evtId={}", agentId, evtId);
            } catch (Exception e) {
                log.warn("failed to persist agent event agent={} evtId={}", agentId, evtId, e);
            }
        }
        return acked;
    }

    private Instant readInstant(JsonNode node) {
        JsonNode ts = Json.first(node, "ts", "time", "at", "eventTime");
        if (ts == null) {
            return Instant.now();
        }
        try {
            if (ts.isNumber()) {
                long v = ts.asLong();
                return v > 1_000_000_000_000L ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
            }
            return Instant.parse(ts.asText());
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
