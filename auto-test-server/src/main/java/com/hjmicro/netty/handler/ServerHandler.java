package com.hjmicro.netty.handler;

import com.hjmicro.domain.dto.RpcDTO;
import com.hjmicro.domain.dto.PingMessage;
import com.hjmicro.domain.dto.PongMessage;
import com.hjmicro.domain.dto.RpcRequest;
import com.hjmicro.domain.dto.RpcResult;
import com.hjmicro.netty.ConnectionRegistry;
import com.hjmicro.netty.ProxyConfiguration;
import com.hjmicro.RpcQueue;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ServerHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(ServerHandler.class);

    private static ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 10, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100));

    private static final int MAX_MISSED_PONGS = 3;

    private static Map<String,ChannelHandlerContext> linkAddressMap = new ConcurrentHashMap<>();



    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg){
        ConnectionRegistry.markSeen(ctx);

        if (msg instanceof PongMessage) {
            ConnectionRegistry.markPong(ctx);
            if (log.isDebugEnabled()) {
                log.debug("Pong received, remoteKey={}", ConnectionRegistry.getRemoteKey(ctx));
            }
            return;
        }
        if (msg instanceof RpcRequest rpcRequest){
            executor.execute(() -> {
                RequestContext.setRequestContext(ctx);
                try {
                    //如果是远程调用请求
                    RpcResult result = new RpcResult();
                    result.setRequestId(rpcRequest.getRequestId());
                    Object o = null;
                    try {
                        o = ProxyConfiguration.invokeMethod(rpcRequest);
                        result.setResult(o);
                    } catch (NoSuchMethodException e) {
                        result.setSuccess(false);
                        result.setMessage("Failed to find the requested method: " + e.getMessage());
                        log.warn("NoSuchMethodException in channelRead0, requestId={}, sign={}",
                                rpcRequest.getRequestId(), rpcRequest.sign, e);
                    } catch (InvocationTargetException e) {
                        result.setSuccess(false);
                        result.setMessage("Error occurred while invoking the target method: " + e.getTargetException());
                        log.warn("InvocationTargetException in channelRead0, requestId={}, sign={}",
                                rpcRequest.getRequestId(), rpcRequest.sign, e.getTargetException());
                    } catch (IllegalAccessException e) {
                        result.setSuccess(false);
                        result.setMessage("The requested method is not accessible: " + e.getMessage());
                        log.warn("IllegalAccessException in channelRead0, requestId={}, sign={}",
                                rpcRequest.getRequestId(), rpcRequest.sign, e);
                    }
                    //封装resul
                    invokeRemoteMethod(result, ctx);
                } finally {
                    RequestContext.clear();
                }
            });
            return;
        }
        if (msg instanceof RpcResult result){
            RpcQueue.signal(result);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.warn("Netty exceptionCaught, remoteKey={}", ConnectionRegistry.getRemoteKey(ctx), cause);
        ctx.close();
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 通过ip注册连接
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String ip = socketAddress.getAddress().getHostAddress();
        int port = socketAddress.getPort();
        linkAddressMap.put(formatRemoteKey(ip, port), ctx);
        ConnectionRegistry.register(ctx);
        log.info("Channel active, remoteKey={}", ConnectionRegistry.getRemoteKey(ctx));
        super.channelActive(ctx);
    }



    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        //通过ip注销
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String ip = socketAddress.getAddress().getHostAddress();
        int port = socketAddress.getPort();
        linkAddressMap.remove(formatRemoteKey(ip, port));
        ConnectionRegistry.unregister(ctx);
        log.info("Channel inactive, remoteKey={}", ConnectionRegistry.getRemoteKey(ctx));
        super.channelInactive(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleStateEvent && idleStateEvent.state() == IdleState.WRITER_IDLE) {
            PingMessage ping = new PingMessage();
            ping.setRequestId(java.util.UUID.randomUUID().toString());
            ping.setTimestamp(System.currentTimeMillis());
            ctx.writeAndFlush(ping);

            ConnectionRegistry.incrementMissedPongs(ctx);
            if (log.isDebugEnabled()) {
                log.debug("Ping sent, requestId={}, remoteKey={}, missedPongs={}",
                        ping.getRequestId(), ConnectionRegistry.getRemoteKey(ctx), ConnectionRegistry.getMissedPongs(ctx));
            }
            if (ConnectionRegistry.getMissedPongs(ctx) >= MAX_MISSED_PONGS) {
                log.warn("Too many missed pongs, closing channel, remoteKey={}", ConnectionRegistry.getRemoteKey(ctx));
                ctx.close();
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    private static String formatRemoteKey(String ip, int port) {
        return ip + ":" + port;
    }

    public static boolean invokeRemoteMethod(RpcDTO rpcRequest, ChannelHandlerContext ctx){
        //通过ip获取连接
        if (ctx == null && rpcRequest instanceof RpcRequest request){
             ctx = linkAddressMap.get(request.getTargetIp());
             if (ctx == null){
                 throw  new RuntimeException("Not Found the connection of the specified ip! ip:" + request.getTargetIp());
             }
        }
        //如果指定了ip且没有连接)
        if(ctx == null && !linkAddressMap.isEmpty()){
            ctx = linkAddressMap.values().stream().findAny().get();
        }
        if (ctx == null){
            throw  new RuntimeException("No available connection!");
        }
        synchronized (ctx){
            //如果没有指定ip，随机选一个
            ctx.writeAndFlush(rpcRequest);
        }
        return true;
    }


}
