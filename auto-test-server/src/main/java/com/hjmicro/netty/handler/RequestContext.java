package com.hjmicro.netty.handler;

import io.netty.channel.ChannelHandlerContext;

public class RequestContext {

    private static ThreadLocal<ChannelHandlerContext> requestContext =
            new ThreadLocal<>();

    public static void setRequestContext(ChannelHandlerContext ctx){
        requestContext.set(ctx);
    }

    public static ChannelHandlerContext getRequestContext(){
        return requestContext.get();
    }

    public static void clear() {
        requestContext.remove();
    }

}
