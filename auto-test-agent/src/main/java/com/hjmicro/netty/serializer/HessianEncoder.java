package com.hjmicro.netty.serializer;

import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianFactory;
import com.hjmicro.netty.serializer.HessianSerializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.ByteArrayOutputStream;
import java.sql.SQLOutput;

public class HessianEncoder extends MessageToByteEncoder<Object> {
    private final HessianFactory factory = new HessianFactory();

    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        // 创建一个流来写入序列化的数据
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


    public static void main(String[] args) {
        Byte[] bytes = {67, 48, 33, 99};
        //ba这个四字节的数组，转换成int类型的数值
        int a = (bytes[0] & 0xff) << 24 | (bytes[1] & 0xff) << 16 | (bytes[2] & 0xff) << 8 | (bytes[3] & 0xff);
        System.out.println(a);
        System.out.println();
    }
}
