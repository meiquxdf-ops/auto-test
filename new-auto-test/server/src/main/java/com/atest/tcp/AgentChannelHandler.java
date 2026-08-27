package com.atest.tcp;

import java.util.concurrent.Executor;

import com.atest.service.AgentSessionService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/** One instance per connection: decodes intent, never blocks the event loop. */
@Slf4j
@ChannelHandler.Sharable
public class AgentChannelHandler extends SimpleChannelInboundHandler<Envelope> {

    private final AgentSessionService sessionService;
    private final Executor workExecutor;

    public AgentChannelHandler(AgentSessionService sessionService, Executor workExecutor) {
        this.sessionService = sessionService;
        this.workExecutor = workExecutor;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        AgentConnection conn = new AgentConnection(ctx.channel(), workExecutor);
        ctx.channel().attr(AgentConnection.ATTR).set(conn);
        log.debug("agent connection opened from {}", conn.getRemoteAddr());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Envelope env) {
        AgentConnection conn = ctx.channel().attr(AgentConnection.ATTR).get();
        if (conn == null) {
            ctx.close();
            return;
        }
        conn.touch();
        if (env.isResponse()) {
            conn.completeResponse(env);
            return;
        }
        if (!env.isRequest()) {
            conn.replyError(env.id, ErrorCodes.BAD_REQUEST, "unknown envelope type: " + env.t);
            return;
        }
        // per connection ordering, shared pool, DB work off the event loop
        conn.serialExecutor().execute(() -> {
            try {
                sessionService.onRequest(conn, env);
            } catch (Exception e) {
                log.error("failed to handle {} from {}", env.m, conn.getAgentId(), e);
                conn.replyError(env.id, ErrorCodes.INTERNAL, String.valueOf(e.getMessage()));
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        AgentConnection conn = ctx.channel().attr(AgentConnection.ATTR).getAndSet(null);
        if (conn == null) {
            return;
        }
        conn.failAllPending(new IllegalStateException("connection closed"));
        workExecutor.execute(() -> {
            try {
                sessionService.onDisconnect(conn);
            } catch (Exception e) {
                log.error("disconnect handling failed for {}", conn.getAgentId(), e);
            }
        });
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            AgentConnection conn = ctx.channel().attr(AgentConnection.ATTR).get();
            log.warn("closing idle agent connection {}", conn == null ? ctx.channel().remoteAddress() : conn);
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("agent channel error from {}: {}", ctx.channel().remoteAddress(), cause.toString());
        ctx.close();
    }
}
