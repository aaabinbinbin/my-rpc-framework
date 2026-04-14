package com.rpc.core.protocol.codec;

import com.rpc.core.protocol.message.RpcHeader;
import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.protocol.message.RpcRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RPC 协议编解码测试。
 *
 * <p>测试目标：验证协议头、序列化类型、消息体在 Netty 编解码链路中的往返一致性，
 * 并覆盖解码异常时 ByteBuf 引用释放，避免异常帧造成内存泄漏。</p>
 */
@DisplayName("RPC 协议编解码测试")
public class RpcProtocolCodecTest {
    private static final Logger log = LoggerFactory.getLogger(RpcProtocolCodecTest.class);

    @DisplayName("验证 RPC 协议编码后可以正常解码还原消息")
    @Test
    public void testEncodeDecode() {
        RpcRequest request = new RpcRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setServiceName("com.rpc.HelloService");
        request.setMethodName("sayHello");
        request.setParameterTypes(new Class[]{String.class});
        request.setParameters(new Object[]{"world"});

        RpcHeader header = RpcHeader.builder()
                .magicNumber(0x12345678)
                .version((byte) 1)
                .messageType((byte) 1)
                .serializerType((byte) 1)
                .requestId(new Random().nextLong())
                .build();

        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(request);
        log.info("编码前 RPC 消息：{}", message);

        EmbeddedChannel encoderChannel = new EmbeddedChannel(new RpcProtocolEncoder());
        assertTrue(encoderChannel.writeOutbound(message));
        ByteBuf encoded = (ByteBuf) encoderChannel.readOutbound();

        EmbeddedChannel decoderChannel = new EmbeddedChannel(new RpcProtocolDecoder());
        assertTrue(decoderChannel.writeInbound(encoded.retainedDuplicate()));
        RpcMessage decoded = decoderChannel.readInbound();

        assertNotNull(decoded);
        assertEquals(0x12345678, decoded.getHeader().getMagicNumber());
        assertEquals(1, decoded.getHeader().getMessageType());

        RpcRequest decodedRequest = (RpcRequest) decoded.getBody();
        assertEquals("com.rpc.HelloService", decodedRequest.getServiceName());
        assertEquals("sayHello", decodedRequest.getMethodName());
        log.info("解码后 RPC 请求：{}", decodedRequest);

        encoderChannel.finishAndReleaseAll();
        decoderChannel.finishAndReleaseAll();
    }

    @DisplayName("验证协议解码失败时会释放 ByteBuf 帧避免内存泄漏")
    @Test
    public void testDecodeFailureShouldReleaseFrame() {
        EmbeddedChannel decoderChannel = new EmbeddedChannel(new RpcProtocolDecoder());
        ByteBuf invalid = Unpooled.buffer();
        invalid.writeInt(0x12345678);
        invalid.writeByte(1);
        invalid.writeByte(1);
        invalid.writeByte(1);
        invalid.writeByte(0);
        invalid.writeLong(1L);
        invalid.writeInt(0);
        invalid.writeInt(4);
        invalid.writeInt(1234);

        ByteBuf inbound = invalid.retainedDuplicate();
        assertEquals(2, invalid.refCnt());
        assertThrows(DecoderException.class, () -> decoderChannel.writeInbound(inbound));
        assertEquals(1, invalid.refCnt());

        invalid.release();
        decoderChannel.finishAndReleaseAll();
    }
}
