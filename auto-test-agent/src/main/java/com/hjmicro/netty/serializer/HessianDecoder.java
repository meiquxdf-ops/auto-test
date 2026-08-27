package com.hjmicro.netty.serializer;

import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.HessianFactory;
import com.hjmicro.netty.serializer.HessianSerializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.io.ByteArrayInputStream;
import java.util.List;

public class HessianDecoder extends ByteToMessageDecoder {

    private HessianSerializer hessianSerializer = new HessianSerializer();


    private final HessianFactory factory = new HessianFactory();

    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object>
            out) throws Exception {
//        ByteBuf in2 = in.retainedDuplicate();
//        byte[] dst;
//        if (in2.hasArray()) {//堆缓冲区模式
//            dst = in2.array();
//        } else {
//            dst = new byte[in2.readableBytes()];
//            in2.getBytes(in2.readerIndex(), dst);
//        }
//        in.skipBytes(in.readableBytes());//跳过所有的字节，表示已经读取过了
//        Object obj = hessianSerializer.deserialize(dst, Object.class);//反序列化
//        out.add(obj);
//
//

        // 确保有足够的字节来读取长度字段
        if (in.readableBytes() < 4) {
            return;
        }

        in.markReaderIndex();
        // 读取长度字段
        int length = in.readInt();

        // 确保有足够的字节来读取数据
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        // 读取数据
        byte[] bytes = new byte[length];
        in.readBytes(bytes);

        // 创建一个流来读取序列化的数据
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        Hessian2Input input = factory.createHessian2Input(bis);

        // 使用Hessian进行反序列化
        Object object = input.readObject();
        input.close();

        out.add(object);

    }


}
