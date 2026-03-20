package com.rpc.protocol.codec;

import com.rpc.codec.RpcProtocolDecoder;
import com.rpc.codec.RpcProtocolEncoder;
import com.rpc.protocol.RpcHeader;
import com.rpc.protocol.RpcMessage;
import com.rpc.protocol.RpcRequest;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.*;

@Slf4j
public class RpcProtocolCodecTest {
    @Test
    public void testEncodeDecode() {
        // 1. 创建测试数据
        RpcRequest request = new RpcRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setServiceName("com.rpc.HelloService");
        request.setMethodName("sayHello");
        request.setParameterTypes(new Class[]{String.class});
        request.setParameters(new Object[]{"world"});

        // 2. 创建消息
        RpcHeader header = RpcHeader.builder()
                .magicNumber(0x12345678)
                .version((byte)1)
                .messageType((byte) 1)      // 请求
                .serializerType((byte) 1)  // Kryo
                .requestId(new Random().nextLong())
                .build();

        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(request);
        log.info("编码前的请求: {}", message);

        // 3. 创建编码通道
        EmbeddedChannel encoderChannel = new EmbeddedChannel(
                new RpcProtocolEncoder()
        );

        // 4. 编码
        assertTrue(encoderChannel.writeOutbound(message));
        ByteBuf encoded = (ByteBuf) encoderChannel.readOutbound();

        // 5. 创建解码通道
        EmbeddedChannel decoderChannel = new EmbeddedChannel(
                new RpcProtocolDecoder()
        );

        // 6. 解码（注意：需要保留引用，因为 writeInbound 会释放）
        assertTrue(decoderChannel.writeInbound(encoded.retainedDuplicate()));
        RpcMessage decoded = (RpcMessage) decoderChannel.readInbound();

        // 7. 验证
        assertNotNull(decoded);
        assertEquals(0x12345678, decoded.getHeader().getMagicNumber());
        assertEquals(1, decoded.getHeader().getMessageType());

        RpcRequest decodedRequest = (RpcRequest) decoded.getBody();
        assertEquals("com.rpc.HelloService", decodedRequest.getServiceName());
        assertEquals("sayHello", decodedRequest.getMethodName());
        log.info("解码后的请求: {}", decodedRequest);

        // 8. 关闭通道
        encoderChannel.finish();
        decoderChannel.finish();
    }
}
