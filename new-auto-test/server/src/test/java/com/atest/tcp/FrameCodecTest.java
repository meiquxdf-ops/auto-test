package com.atest.tcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.atest.common.Json;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

    private EmbeddedChannel channel() {
        EmbeddedChannel ch = new EmbeddedChannel();
        FrameCodec.install(ch.pipeline(), 1024 * 1024);
        return ch;
    }

    @Test
    void decodesLengthPrefixedJson() {
        EmbeddedChannel ch = channel();
        byte[] payload = "{\"v\":1,\"t\":\"req\",\"id\":42,\"m\":\"hello\",\"a\":{\"agentId\":\"a-1\"}}"
                .getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(payload.length);
        buf.writeBytes(payload);

        assertThat(ch.writeInbound(buf)).isTrue();
        Envelope env = ch.readInbound();
        assertThat(env.isRequest()).isTrue();
        assertThat(env.id).isEqualTo(42L);
        assertThat(env.m).isEqualTo("hello");
        assertThat(env.args().get("agentId").asText()).isEqualTo("a-1");
    }

    @Test
    void encodesLengthPrefixedJson() {
        EmbeddedChannel ch = channel();
        ch.writeOutbound(Envelope.req(7, "exec", Map.of("executeId", "e-1")));

        // LengthFieldPrepender emits the header and the payload as two buffers
        ByteBuf header = ch.readOutbound();
        ByteBuf body = ch.readOutbound();
        int length = header.readInt();
        assertThat(length).isEqualTo(body.readableBytes());
        String json = body.toString(StandardCharsets.UTF_8);
        assertThat(Json.read(json).get("m").asText()).isEqualTo("exec");
        assertThat(Json.read(json).get("v").asInt()).isEqualTo(1);
    }

    @Test
    void waitsForTheWholeFrame() {
        EmbeddedChannel ch = channel();
        byte[] payload = "{\"v\":1,\"t\":\"rsp\",\"id\":1,\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        ByteBuf head = Unpooled.buffer().writeInt(payload.length);
        head.writeBytes(payload, 0, 5);
        assertThat(ch.writeInbound(head)).isFalse();

        ByteBuf tail = Unpooled.copiedBuffer(payload, 5, payload.length - 5);
        assertThat(ch.writeInbound(tail)).isTrue();
        Envelope env = ch.readInbound();
        assertThat(env.isResponse()).isTrue();
        assertThat(env.isOk()).isTrue();
    }

    @Test
    void errorEnvelopeCarriesCode() {
        Envelope env = Envelope.error(3L, ErrorCodes.DUP_SESSION, "already connected");
        assertThat(env.isOk()).isFalse();
        assertThat(env.errorCode()).isEqualTo("dup_session");
        String json = Json.write(env);
        assertThat(json).contains("\"c\":\"dup_session\"").doesNotContain("\"r\":");
    }
}
