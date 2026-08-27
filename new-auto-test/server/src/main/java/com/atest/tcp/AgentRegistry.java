package com.atest.tcp;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.atest.config.AtestProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * agentId -> exactly one live connection.
 *
 * <p>When a second connection arrives for the same agentId the incumbent is pinged; if it answers
 * within the configured window the newcomer is rejected with {@code dup_session}, otherwise the
 * newcomer takes over. Nothing here ever picks a connection by IP or at random.
 */
@Slf4j
@Component
public class AgentRegistry {

    public enum Outcome {
        ACCEPTED,
        TAKEOVER,
        REJECTED_DUP
    }

    private final AtestProperties props;
    private final Map<String, AgentConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<?>> arbitrationChains = new HashMap<>();
    private final Object chainLock = new Object();

    public AgentRegistry(AtestProperties props) {
        this.props = props;
    }

    public CompletableFuture<Outcome> register(AgentConnection conn) {
        String agentId = conn.getAgentId();
        if (agentId == null || agentId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("agentId is required"));
        }
        return chain(agentId, () -> arbitrate(agentId, conn));
    }

    private CompletableFuture<Outcome> arbitrate(String agentId, AgentConnection conn) {
        AgentConnection incumbent = connections.get(agentId);
        if (incumbent == conn) {
            return CompletableFuture.completedFuture(Outcome.ACCEPTED);
        }
        if (incumbent == null || !incumbent.isActive()) {
            accept(agentId, conn, incumbent);
            return CompletableFuture.completedFuture(incumbent == null ? Outcome.ACCEPTED : Outcome.TAKEOVER);
        }
        long timeoutMs = props.getAgent().getDupSessionPingTimeoutMs();
        log.info("dup session for agent={}, pinging incumbent {} ({}ms)", agentId, incumbent.getSessionId(), timeoutMs);
        return incumbent.request("ping", Map.of("reason", "dup_session"), timeoutMs)
                .handle((rsp, err) -> {
                    boolean alive = err == null && rsp != null && rsp.isOk() && incumbent.isActive();
                    if (alive) {
                        log.warn("incumbent connection for agent={} is alive, rejecting new session", agentId);
                        return Outcome.REJECTED_DUP;
                    }
                    log.warn("incumbent connection for agent={} did not answer ping, taking over", agentId);
                    accept(agentId, conn, incumbent);
                    return Outcome.TAKEOVER;
                });
    }

    private void accept(String agentId, AgentConnection conn, AgentConnection incumbent) {
        connections.put(agentId, conn);
        conn.setRegistered(true);
        if (incumbent != null && incumbent != conn) {
            incumbent.setRegistered(false);
            incumbent.failAllPending(new IllegalStateException("session replaced"));
            incumbent.close();
        }
    }

    /** Serializes hello handling per agentId without blocking a thread while the ping is in flight. */
    private CompletableFuture<Outcome> chain(String agentId, Supplier<CompletableFuture<Outcome>> task) {
        synchronized (chainLock) {
            CompletableFuture<?> previous = arbitrationChains.get(agentId);
            CompletableFuture<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((r, t) -> (Void) null);
            CompletableFuture<Outcome> next = ready.thenCompose(ignored -> task.get());
            CompletableFuture<?> tracked = next.handle((r, t) -> null);
            arbitrationChains.put(agentId, tracked);
            tracked.whenComplete((r, t) -> {
                synchronized (chainLock) {
                    if (arbitrationChains.get(agentId) == tracked) {
                        arbitrationChains.remove(agentId);
                    }
                }
            });
            return next;
        }
    }

    /** Only clears the mapping when the given connection is still the registered one. */
    public boolean unregister(AgentConnection conn) {
        String agentId = conn.getAgentId();
        if (agentId == null) {
            return false;
        }
        return connections.remove(agentId, conn);
    }

    public Optional<AgentConnection> get(String agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        AgentConnection conn = connections.get(agentId);
        return conn != null && conn.isActive() ? Optional.of(conn) : Optional.empty();
    }

    public boolean isOnline(String agentId) {
        return get(agentId).isPresent();
    }

    public Set<String> onlineAgentIds() {
        return Set.copyOf(connections.keySet());
    }

    public int size() {
        return connections.size();
    }
}
