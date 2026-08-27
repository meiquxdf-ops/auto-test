package com.hjmicro.netty;

import com.hjmicro.RpcQueue;
import com.hjmicro.ServiceInterface;
import com.hjmicro.domain.dto.RpcDTO;
import com.hjmicro.domain.dto.RpcRequest;
import com.hjmicro.domain.dto.RpcResult;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import org.apache.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class SerializedSendServer {
    private static final Logger logger = Logger.getLogger(SerializedSendServer.class);

    private static final Object CTX_LOCK = new Object();
    private static volatile ChannelHandlerContext ctx;

    public static void setCtx(ChannelHandlerContext ctx) {
        synchronized (CTX_LOCK) {
            SerializedSendServer.ctx = ctx;
            CTX_LOCK.notifyAll();
        }
        try {
            if (ctx == null) {
                logger.debug("[Netty] ctx cleared (disconnected)");
            } else {
                logger.debug("[Netty] ctx set, remote=" + ctx.channel().remoteAddress());
            }
        } catch (Exception e) {
            logger.warn("Failed to log ctx change", e);
        }
    }

    static LinkedBlockingQueue<RpcDTO> requestQueue = new LinkedBlockingQueue<RpcDTO>();

    private static Thread requestQueueThread;

    static {
        requestQueueThread = new Thread(SerializedSendServer::requestQueueStart);
        requestQueueThread.start();
    }

    public static <T extends ServiceInterface> void sendOneway(Class<T> aclass, String methodName, Object... args) {
        ChannelHandlerContext localCtx = ctx;
        if (localCtx == null || !localCtx.channel().isActive()) {
            throw new IllegalStateException("Netty channel is not connected");
        }

        Class<?>[] parameterTypes = Arrays.stream(args)
                .map(Object::getClass)
                .toArray(Class<?>[]::new);
        Method method;
        try {
            method = aclass.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            logger.warn("[RPC] NoSuchMethodException in sendOneway: " + e.getMessage());
            throw new RuntimeException(e);
        }

        RpcRequest rpcRequest = new RpcRequest();
        rpcRequest.setArgs(args);
        rpcRequest.setTargetIp(localCtx.channel().remoteAddress().toString());
        rpcRequest.setRequestId(UUID.randomUUID().toString());
        rpcRequest.setSign(aclass.getName() + "#" + method.getName() + "#" + Arrays.toString(method.getParameterTypes()));

        ChannelFuture future = localCtx.writeAndFlush(rpcRequest);
        future.addListener(f -> {
            if (!f.isSuccess()) {
                logger.warn("Failed to send one-way rpc message, sign=" + rpcRequest.getSign()
                        + ", requestId=" + rpcRequest.getRequestId(), f.cause());
            }
        });
    }

    public static <T extends ServiceInterface> Object send(Class<T> aclass, String methodName, Object... args) {
        ChannelHandlerContext localCtx = ctx;
        if (localCtx == null || !localCtx.channel().isActive()) {
            throw new IllegalStateException("Netty channel is not connected");
        }

        Class<?>[] parameterTypes = Arrays.stream(args)
                .map(Object::getClass)
                .toArray(Class<?>[]::new);
        Method method;
        try {
            method = aclass.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            logger.warn("[RPC] NoSuchMethodException in send: " + e.getMessage());
            throw new RuntimeException(e);
        }

        RpcRequest rpcRequest = new RpcRequest();
        rpcRequest.setArgs(args);
        rpcRequest.setTargetIp(localCtx.channel().remoteAddress().toString());
        String requestId = UUID.randomUUID().toString();
        rpcRequest.setRequestId(requestId);
        String sign = aclass.getName() + "#" + method.getName() + "#" + Arrays.toString(method.getParameterTypes());
        rpcRequest.setSign(sign);

        try {
            requestQueue.put(rpcRequest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        RpcResult rpcResult = RpcQueue.waitReturn(rpcRequest);
        return rpcResult.getResult();
    }

    public static void put(RpcDTO rpcDTO) {
        try {
            requestQueue.put(rpcDTO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static void requestQueueStart() {
        while (true) {
            try {
                RpcDTO request = requestQueue.take();
                ChannelHandlerContext localCtx = awaitActiveCtx();

                ChannelFuture future = localCtx.writeAndFlush(request);
                future.awaitUninterruptibly(3, TimeUnit.SECONDS);
                if (!future.isSuccess()) {
                    logger.warn("Failed to send rpc message", future.cause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Unexpected error while sending rpc message", e);
            }
        }
    }

    private static ChannelHandlerContext awaitActiveCtx() throws InterruptedException {
        synchronized (CTX_LOCK) {
            while (ctx == null || !ctx.channel().isActive()) {
                CTX_LOCK.wait(1000);
            }
            return ctx;
        }
    }
}
