package com.hjmicro.netty;


import com.hjmicro.netty.handler.ServerHandler;
import com.hjmicro.netty.serialization.HessianDecoder;
import com.hjmicro.netty.serialization.HessianEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
@Slf4j
public class NettyStart {

    @Value("${netty.port}")
    private int port;




    @PostConstruct
    public void startNettyServer() throws Exception {
        new Thread(()->{
            EventLoopGroup bossGroup = new NioEventLoopGroup();
            EventLoopGroup workerGroup = new NioEventLoopGroup();
            try {
                ServerBootstrap bootstrap = new ServerBootstrap()
                        .group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            public void initChannel(SocketChannel ch) throws Exception {
                                ch.pipeline().addLast(
//                                        new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4),
                                        new IdleStateHandler(0, 60, 0),
                                        new HessianDecoder(),
                                        new HessianEncoder(),
                                        new ServerHandler());
                            }
                        });
                ChannelFuture future = bootstrap.bind(port).sync();
                log.info("Netty server started on port {}", port);
                future.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                try {
                    workerGroup.shutdownGracefully().sync();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                try {
                    bossGroup.shutdownGracefully().sync();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }).start();
    }


}
