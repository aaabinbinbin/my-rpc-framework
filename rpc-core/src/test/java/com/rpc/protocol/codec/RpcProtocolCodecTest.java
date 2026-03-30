package com.rpc.core.protocol.codec;

import com.rpc.core.protocol.codec.RpcProtocolDecoder;
import com.rpc.core.protocol.codec.RpcProtocolEncoder;
import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.RpcRequest;
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
        // 1. 鍒涘缓娴嬭瘯鏁版嵁
        RpcRequest request = new RpcRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setServiceName("com.rpc.HelloService");
        request.setMethodName("sayHello");
        request.setParameterTypes(new Class[]{String.class});
        request.setParameters(new Object[]{"world"});

        // 2. 鍒涘缓娑堟伅
        RpcHeader header = RpcHeader.builder()
                .magicNumber(0x12345678)
                .version((byte)1)
                .messageType((byte) 1)      // 璇锋眰
                .serializerType((byte) 1)  // Kryo
                .requestId(new Random().nextLong())
                .build();

        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(request);
        log.info("缂栫爜鍓嶇殑璇锋眰: {}", message);

        // 3. 鍒涘缓缂栫爜閫氶亾
        EmbeddedChannel encoderChannel = new EmbeddedChannel(
                new RpcProtocolEncoder()
        );

        // 4. 缂栫爜
        assertTrue(encoderChannel.writeOutbound(message));
        ByteBuf encoded = (ByteBuf) encoderChannel.readOutbound();

        // 5. 鍒涘缓瑙ｇ爜閫氶亾
        EmbeddedChannel decoderChannel = new EmbeddedChannel(
                new RpcProtocolDecoder()
        );

        // 6. 瑙ｇ爜锛堟敞鎰忥細闇€瑕佷繚鐣欏紩鐢紝鍥犱负 writeInbound 浼氶噴鏀撅級
        assertTrue(decoderChannel.writeInbound(encoded.retainedDuplicate()));
        RpcMessage decoded = (RpcMessage) decoderChannel.readInbound();

        // 7. 楠岃瘉
        assertNotNull(decoded);
        assertEquals(0x12345678, decoded.getHeader().getMagicNumber());
        assertEquals(1, decoded.getHeader().getMessageType());

        RpcRequest decodedRequest = (RpcRequest) decoded.getBody();
        assertEquals("com.rpc.HelloService", decodedRequest.getServiceName());
        assertEquals("sayHello", decodedRequest.getMethodName());
        log.info("瑙ｇ爜鍚庣殑璇锋眰: {}", decodedRequest);

        // 8. 鍏抽棴閫氶亾
        encoderChannel.finish();
        decoderChannel.finish();
    }
}

