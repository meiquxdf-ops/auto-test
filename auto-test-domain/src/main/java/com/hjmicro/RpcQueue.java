package com.hjmicro;

import com.hjmicro.domain.dto.RpcRequest;
import com.hjmicro.domain.dto.RpcResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RpcQueue {

    private static final ConcurrentHashMap<String, CompletableFuture<RpcResult>> pending =
            new ConcurrentHashMap<>();

    public static RpcResult waitReturn(RpcRequest rpcRequest) {
        String requestId = rpcRequest.getRequestId();
        CompletableFuture<RpcResult> future =
                pending.computeIfAbsent(requestId, k -> new CompletableFuture<>());
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("rpc timeout, requestId=" + requestId, e);
        } catch (Exception e) {
            throw new RuntimeException("rpc failed, requestId=" + requestId, e);
        } finally {
            pending.remove(requestId, future);
        }
    }

    public static boolean signal(RpcResult rpcResult) {
        String requestId = rpcResult.getRequestId();
        CompletableFuture<RpcResult> future = pending.get(requestId);
        if (future == null) {
            return false;
        }
        return future.complete(rpcResult);
    }

}
