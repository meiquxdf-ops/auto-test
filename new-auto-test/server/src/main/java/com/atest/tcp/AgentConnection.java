package com.atest.tcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One live agent TCP session. */
public class AgentConnection {

    public static final AttributeKey<AgentConnection> ATTR = AttributeKey.valueOf("atest.conn");

    private static final Logger log = LoggerFactory.getLogger(AgentConnection.class);

    private final Channel channel;
    private final String remoteAddr;
    private final SerialExecutor serialExecutor;
    private final AtomicLong reqSeq = new AtomicLong();
    private final Map<Long, CompletableFuture<Envelope>> pending = new ConcurrentHashMap<>();
    private final long connectedAtMs = System.currentTimeMillis();

    private volatile String agentId;
    private volatile String sessionId;
    private volatile String bootId;
    private volatile String version;
    private volatile List<String> aliases = List.of();
    private volatile boolean registered;
    private volatile long lastFrameAtMs = System.currentTimeMillis();
    private volatile long lastHeartbeatAtMs;

    public AgentConnection(Channel channel, java.util.concurrent.Executor workExecutor) {
        this.channel = channel;
        String remote = String.valueOf(channel.remoteAddress());
        this.remoteAddr = remote.startsWith("/") ? remote.substring(1) : remote;
        this.serialExecutor = new SerialExecutor(workExecutor);
    }

    public Channel channel() {
        return channel;
    }

    public SerialExecutor serialExecutor() {
        return serialExecutor;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getBootId() {
        return bootId;
    }

    public void setBootId(String bootId) {
        this.bootId = bootId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public boolean isRegistered() {
        return registered;
    }

    public void setRegistered(boolean registered) {
        this.registered = registered;
    }

    public String getRemoteAddr() {
        return remoteAddr;
    }

    public long getConnectedAtMs() {
        return connectedAtMs;
    }

    public long getLastFrameAtMs() {
        return lastFrameAtMs;
    }

    public void touch() {
        this.lastFrameAtMs = System.currentTimeMillis();
    }

    public long getLastHeartbeatAtMs() {
        return lastHeartbeatAtMs;
    }

    public void markHeartbeat() {
        this.lastHeartbeatAtMs = System.currentTimeMillis();
        touch();
    }

    public boolean isActive() {
        return channel.isActive();
    }

    /** Server initiated request (exec / cancel / stop / ping). */
    public CompletableFuture<Envelope> request(String method, Object args, long timeoutMs) {
        long id = reqSeq.incrementAndGet();
        CompletableFuture<Envelope> future = new CompletableFuture<>();
        pending.put(id, future);
        if (!channel.isActive()) {
            pending.remove(id);
            future.completeExceptionally(new IllegalStateException("connection closed"));
            return future;
        }
        ScheduledFuture<?> timeout = channel.eventLoop().schedule(() -> {
            CompletableFuture<Envelope> f = pending.remove(id);
            if (f != null) {
                f.completeExceptionally(new TimeoutException(method + " timed out after " + timeoutMs + "ms"));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        future.whenComplete((r, t) -> timeout.cancel(false));

        channel.writeAndFlush(Envelope.req(id, method, args)).addListener(f -> {
            if (!f.isSuccess()) {
                CompletableFuture<Envelope> pf = pending.remove(id);
                if (pf != null) {
                    pf.completeExceptionally(f.cause() == null
                            ? new IllegalStateException("write failed") : f.cause());
                }
            }
        });
        return future;
    }

    /** Completes the waiting caller for an inbound rsp frame. */
    public void completeResponse(Envelope rsp) {
        if (rsp.id == null) {
            return;
        }
        CompletableFuture<Envelope> future = pending.remove(rsp.id);
        if (future != null) {
            future.complete(rsp);
        } else {
            log.debug("stale rsp id={} from agent={}", rsp.id, agentId);
        }
    }

    public void reply(Long id, Object result) {
        if (id == null) {
            return;
        }
        channel.writeAndFlush(Envelope.ok(id, result));
    }

    public void replyError(Long id, String code, String message) {
        if (id == null) {
            return;
        }
        channel.writeAndFlush(Envelope.error(id, code, message));
    }

    public void replyErrorAndClose(Long id, String code, String message) {
        if (id == null) {
            channel.close();
            return;
        }
        channel.writeAndFlush(Envelope.error(id, code, message))
                .addListener(f -> channel.close());
    }

    public void close() {
        channel.close();
    }

    public void failAllPending(Throwable cause) {
        pending.forEach((id, future) -> future.completeExceptionally(cause));
        pending.clear();
    }

    @Override
    public String toString() {
        return "AgentConnection{agentId=" + agentId + ", session=" + sessionId + ", remote=" + remoteAddr + "}";
    }
}
