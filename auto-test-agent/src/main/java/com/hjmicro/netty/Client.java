package com.hjmicro.netty;

import com.hjmicro.netty.serializer.HessianDecoder;
import com.hjmicro.netty.serializer.HessianEncoder;
import com.hjmicro.netty.handler.ClientHandler;
import com.hjmicro.service.AgentEventLogger;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.apache.log4j.Logger;


public class Client {

    private static Logger logger = Logger.getLogger(Client.class);
    private static final long RECONNECT_DELAY_MS = 2_000L;
    private static final long CONNECT_WARN_INTERVAL_MS = 10_000L;
    private final String host;
    private final int port;

    public Client(String host, int port) {
        this.host = host;
        this.port = port;
        AgentEventLogger.setServer(host, port);
    }

    public void start() throws Exception {
        ClientHandler clientHandler = new ClientHandler();
        clientHandler.initClientHandler();
        EventLoopGroup group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(
//                                new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 4, 4, 0, 4),
                                new HessianDecoder(),
                                new HessianEncoder(),
                                clientHandler);
                    }
                });

        long lastConnectWarnAt = 0L;
        try {
            while (true){
                try {
                    AgentEventLogger.info("server_connecting", "connecting to server " + host + ":" + port);
                    ChannelFuture future = bootstrap.connect(host, port).sync();
                    Channel channel = future.channel();
                    logger.info("[Connection] Connected to server " + host + ":" + port);
                    lastConnectWarnAt = 0L;
//                    channel.writeAndFlush("123123");
                    channel.closeFuture().sync();
                    AgentEventLogger.info("server_reconnect_scheduled",
                            "server channel closed, reconnect after " + RECONNECT_DELAY_MS + "ms");
                } catch (Exception e){
                    long now = System.currentTimeMillis();
                    if (now - lastConnectWarnAt >= CONNECT_WARN_INTERVAL_MS) {
                        logger.warn("[Connection] Connect failed, will retry. server=" + host + ":" + port
                                + ", error=" + e.getMessage());
                        AgentEventLogger.error("server_connect_failed",
                                "connect failed, will retry. server=" + host + ":" + port, e);
                        lastConnectWarnAt = now;
                    } else if (logger.isDebugEnabled()) {
                        logger.debug("[Connection] Connect failed, will retry. server=" + host + ":" + port, e);
                    }
                    AgentEventLogger.info("server_reconnect_scheduled",
                            "connect failed, reconnect after " + RECONNECT_DELAY_MS + "ms");
                }
                Thread.sleep(RECONNECT_DELAY_MS);
            }
        } finally {
            try {
                group.shutdownGracefully().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }


    public static void main(String[] args) throws Exception {
        logger.info("Client start...");
        String host = System.getProperty("host");
        int port = Integer.parseInt(System.getProperty("port"));
        AgentEventLogger.setServer(host, port);
        AgentEventLogger.info("agent_starting",
                "agent starting, java=" + System.getProperty("java.version")
                        + ", user.dir=" + System.getProperty("user.dir")
                        + ", server=" + host + ":" + port);
        new Client(host, port).start();
    }


}
