package com.rpc.core.transport.socket.legacy;

import com.rpc.core.extension.serialize.impl.KryoSerializer;
import com.rpc.core.protocol.message.RpcHeader;
import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.protocol.message.RpcRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * legacy Socket 编解码测试。
 *
 * <p>测试目标：验证非 Netty 传输路径中 serializerType + payloadLength + payload 的帧格式，
 * 并覆盖半包读取失败的边界，避免错误地把不完整 payload 交给反序列化器处理。</p>
 */
@DisplayName("legacy Socket 编解码测试")
class SocketMessageCodecTest {

    @Test
    @DisplayName("Socket 编解码应保持消息头和请求体数据一致")
    void shouldRoundTripRpcMessage() throws IOException {
        RpcMessage message = buildMessage();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        SocketMessageCodec.writeMessage(new DataOutputStream(bytes), message);
        RpcMessage decoded = SocketMessageCodec.readMessage(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))
        );

        assertEquals(message.getHeader().getSerializerType(), decoded.getHeader().getSerializerType());
        assertEquals(message.getHeader().getRequestId(), decoded.getHeader().getRequestId());
        assertInstanceOf(RpcRequest.class, decoded.getBody());

        RpcRequest request = (RpcRequest) decoded.getBody();
        assertEquals("request-1", request.getRequestId());
        assertEquals("demoService", request.getServiceName());
        assertEquals("echo", request.getMethodName());
        assertArrayEquals(new Object[]{"hello"}, request.getParameters());
    }

    @Test
    @DisplayName("Socket 解码遇到半包时应抛出 IOException")
    void shouldRejectTruncatedPayload() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream outputStream = new DataOutputStream(bytes);
        outputStream.writeByte(KryoSerializer.TYPE_KRYO);
        outputStream.writeInt(10);
        outputStream.write(new byte[]{1, 2, 3});

        DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));

        assertThrows(IOException.class, () -> SocketMessageCodec.readMessage(inputStream));
    }

    private RpcMessage buildMessage() {
        RpcHeader header = RpcHeader.builder()
                .serializerType((byte) KryoSerializer.TYPE_KRYO)
                .messageType((byte) 1)
                .requestId(1001L)
                .build();
        RpcRequest request = RpcRequest.builder()
                .requestId("request-1")
                .serviceName("demoService")
                .methodName("echo")
                .parameterTypes(new Class<?>[]{String.class})
                .parameters(new Object[]{"hello"})
                .returnType(String.class)
                .build();
        return RpcMessage.builder()
                .header(header)
                .body(request)
                .build();
    }
}
