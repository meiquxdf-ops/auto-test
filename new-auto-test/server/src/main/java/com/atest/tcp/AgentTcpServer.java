package com.atest.tcp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.atest.config.AtestProperties;
import com.atest.service.AgentSessionService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Agent facing TCP endpoint (default :9800). */
@Slf4j
@Component
public class AgentTcpServer {

    private final AtestProperties props;
    private final AgentSessionService sessionService;
    private final ExecutorService workExecutor;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public AgentTcpServer(AtestProperties props,
                          AgentSessionService sessionService,
                          @Qualifier("agentWorkExecutor") ExecutorService workExecutor) {
        this.props = props;
        this.sessionService = sessionService;
        this.workExecutor = workExecutor;
    }

    @PostConstruct
    public void start() throws InterruptedException {
        AtestProperties.Agent cfg = props.getAgent();
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("agent-boss"));
        workerGroup = new NioEventLoopGroup(Math.max(2, Runtime.getRuntime().availableProcessors()),
                new DefaultThreadFactory("agent-io"));
        AgentChannelHandler handler = new AgentChannelHandler(sessionService, workExecutor);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("idle",
                                new IdleStateHandler(cfg.getIdleTimeoutSec(), 0, 0, TimeUnit.SECONDS));
                        FrameCodec.install(ch.pipeline(), cfg.getMaxFrameBytes());
                        ch.pipeline().addLast("agent", handler);
                    }
                });

        serverChannel = bootstrap.bind(cfg.getBindAddress(), cfg.getPort()).sync().channel();
        int actualPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
        log.info("agent TCP server listening on {}:{}", cfg.getBindAddress(), actualPort);
    }

    public int boundPort() {
        if (serverChannel == null) {
            return -1;
        }
        return ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @PreDestroy
    public void stop() {
        log.info("stopping agent TCP server");
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
    }
}
