package com.hjmicro.netty;

import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private static final ConcurrentHashMap<String, ChannelHandlerContext> remoteKeyToCtx =
            new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, String> channelIdToRemoteKey =
            new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Long> lastSeenByRemoteKey =
            new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Long> lastPongByRemoteKey =
            new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Integer> missedPongsByRemoteKey =
            new ConcurrentHashMap<>();

    public static void register(ChannelHandlerContext ctx) {
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        String remoteKey = formatRemoteKey(socketAddress);
        remoteKeyToCtx.put(remoteKey, ctx);
        channelIdToRemoteKey.put(ctx.channel().id().asShortText(), remoteKey);
        markSeen(remoteKey);
        log.info("Connection registered, remoteKey={}, channelId={}", remoteKey, ctx.channel().id().asShortText());
    }

    public static void unregister(ChannelHandlerContext ctx) {
        String remoteKey = channelIdToRemoteKey.remove(ctx.channel().id().asShortText());
        if (remoteKey == null) {
            InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            remoteKey = formatRemoteKey(socketAddress);
        }
        remoteKeyToCtx.remove(remoteKey, ctx);
        lastSeenByRemoteKey.remove(remoteKey);
        lastPongByRemoteKey.remove(remoteKey);
        missedPongsByRemoteKey.remove(remoteKey);
        log.info("Connection unregistered, remoteKey={}, channelId={}", remoteKey, ctx.channel().id().asShortText());
    }

    public static void markSeen(ChannelHandlerContext ctx) {
        String remoteKey = channelIdToRemoteKey.get(ctx.channel().id().asShortText());
        if (remoteKey != null) {
            markSeen(remoteKey);
        }
    }

    public static void markPong(ChannelHandlerContext ctx) {
        String remoteKey = channelIdToRemoteKey.get(ctx.channel().id().asShortText());
        if (remoteKey == null) {
            return;
        }
        lastPongByRemoteKey.put(remoteKey, System.currentTimeMillis());
        missedPongsByRemoteKey.put(remoteKey, 0);
        markSeen(remoteKey);
        if (log.isDebugEnabled()) {
            log.debug("Pong received, remoteKey={}, channelId={}", remoteKey, ctx.channel().id().asShortText());
        }
    }

    public static void incrementMissedPongs(ChannelHandlerContext ctx) {
        String remoteKey = channelIdToRemoteKey.get(ctx.channel().id().asShortText());
        if (remoteKey == null) {
            return;
        }
        missedPongsByRemoteKey.merge(remoteKey, 1, Integer::sum);
        if (log.isDebugEnabled()) {
            log.debug("Missed pong incremented, remoteKey={}, missed={}", remoteKey, missedPongsByRemoteKey.get(remoteKey));
        }
    }

    public static int getMissedPongs(ChannelHandlerContext ctx) {
        String remoteKey = channelIdToRemoteKey.get(ctx.channel().id().asShortText());
        if (remoteKey == null) {
            return 0;
        }
        return missedPongsByRemoteKey.getOrDefault(remoteKey, 0);
    }

    public static boolean isRemoteActive(String remoteKey) {
        if (remoteKey == null) {
            return false;
        }
        ChannelHandlerContext ctx = remoteKeyToCtx.get(remoteKey);
        return ctx != null && ctx.channel().isActive();
    }

    public static String getRemoteKey(ChannelHandlerContext ctx) {
        if (ctx == null) {
            return null;
        }
        String remoteKey = channelIdToRemoteKey.get(ctx.channel().id().asShortText());
        if (remoteKey != null) {
            return remoteKey;
        }
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        return formatRemoteKey(socketAddress);
    }

    public static ChannelHandlerContext getByRemoteKey(String remoteKey) {
        ChannelHandlerContext ctx = remoteKeyToCtx.get(remoteKey);
        if (ctx == null || !ctx.channel().isActive()) {
            return null;
        }
        return ctx;
    }

    public static Long getLastPongByRemoteKey(String remoteKey) {
        return lastPongByRemoteKey.get(remoteKey);
    }

    private static void markSeen(String remoteKey) {
        lastSeenByRemoteKey.put(remoteKey, System.currentTimeMillis());
    }

    private static String formatRemoteKey(InetSocketAddress socketAddress) {
        if (socketAddress == null || socketAddress.getAddress() == null) {
            return String.valueOf(socketAddress);
        }
        return socketAddress.getAddress().getHostAddress() + ":" + socketAddress.getPort();
    }
}
