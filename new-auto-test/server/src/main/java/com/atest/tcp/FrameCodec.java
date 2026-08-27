package com.atest.tcp;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.atest.common.Json;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;

/** [4 byte big endian length N][N bytes UTF-8 JSON], one frame is at most 1MiB. */
public final class FrameCodec {

    private FrameCodec() {
    }

    public static void install(ChannelPipeline pipeline, int maxFrameBytes) {
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(maxFrameBytes, 0, 4, 0, 4));
        pipeline.addLast("envelopeDecoder", new EnvelopeDecoder());
        pipeline.addLast("lengthPrepender", new LengthFieldPrepender(4));
        pipeline.addLast("envelopeEncoder", new EnvelopeEncoder(maxFrameBytes));
    }

    public static class EnvelopeDecoder extends MessageToMessageDecoder<ByteBuf> {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
            String json = msg.toString(StandardCharsets.UTF_8);
            Envelope env = Json.mapper().readValue(json, Envelope.class);
            out.add(env);
        }
    }

    public static class EnvelopeEncoder extends MessageToByteEncoder<Envelope> {

        private final int maxFrameBytes;

        public EnvelopeEncoder(int maxFrameBytes) {
            this.maxFrameBytes = maxFrameBytes;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, Envelope msg, ByteBuf out) throws Exception {
            byte[] payload = Json.mapper().writeValueAsBytes(msg);
            if (payload.length > maxFrameBytes) {
                throw new IllegalStateException("frame too large: " + payload.length + " > " + maxFrameBytes);
            }
            out.writeBytes(payload);
        }
    }
}
