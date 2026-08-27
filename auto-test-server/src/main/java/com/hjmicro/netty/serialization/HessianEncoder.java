package com.hjmicro.netty.serialization;

import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;

public class HessianEncoder extends MessageToByteEncoder<Object> {
    private HessianFactory factory = new HessianFactory();

    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Hessian2Output output = factory.createHessian2Output(bos);

        // 使用Hessian进行序列化
        output.writeObject(msg);
        output.close();

        // 获取序列化后的字节
        byte[] bytes = bos.toByteArray();

        // 写入数据的长度，以便在接收端解决粘包问题
        out.writeInt(bytes.length);
        // 写入数据
        out.writeBytes(bytes);
    }
}
