package com.rpc.core.transport.netty.server.handler;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.protocol.message.RpcHeader;
import com.rpc.core.protocol.message.RpcHeartbeat;
import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.protocol.message.RpcMessageType;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：RPC请求处理器测试")
class RpcRequestHandlerTest {
    @DisplayName("验证返回繁忙响应当业务执行器拒绝请求场景")
    @Test
    void shouldReturnBusyResponseWhenBizExecutorRejectsRequest() {
        RpcRequestHandler handler = new RpcRequestHandler(message -> null, command -> {
            throw new RejectedExecutionException("busy");
        });
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(buildRequestMessage("1001"));

        RpcMessage responseMessage = channel.readOutbound();
        RpcResponse response = assertInstanceOf(RpcResponse.class, responseMessage.getBody());
        assertEquals(ErrorCode.SERVER_BUSY.getCode(), response.getCode());
        assertEquals("1001", response.getRequestId());
        assertEquals(RpcMessageType.RESPONSE.getCode(), responseMessage.getHeader().getMessageType());
    }

    @DisplayName("验证绕过业务执行器用于心跳请求场景")
    @Test
    void shouldBypassBizExecutorForHeartbeatRequest() {
        RpcRequestHandler handler = new RpcRequestHandler(message -> buildHeartbeatResponse(message), command -> {
            throw new RejectedExecutionException("busy");
        });
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(buildHeartbeatMessage(1002L));

        RpcMessage responseMessage = channel.readOutbound();
        RpcHeartbeat response = assertInstanceOf(RpcHeartbeat.class, responseMessage.getBody());
        assertEquals(1002L, response.getRequestId());
        assertEquals(RpcMessageType.HEARTBEAT_RESPONSE.getCode(), responseMessage.getHeader().getMessageType());
    }

    private RpcMessage buildRequestMessage(String requestId) {
        RpcRequest request = RpcRequest.builder()
                .requestId(requestId)
                .serviceName("demoService")
                .methodName("echo")
                .build();
        return RpcMessage.builder()
                .header(RpcHeader.builder()
                        .serializerType((byte) 1)
                        .messageType(RpcMessageType.REQUEST.getCode())
                        .requestId(Long.parseLong(requestId))
                        .build())
                .body(request)
                .build();
    }

    private RpcMessage buildHeartbeatMessage(long requestId) {
        return RpcMessage.builder()
                .header(RpcHeader.builder()
                        .serializerType((byte) 0)
                        .messageType(RpcMessageType.HEARTBEAT_REQUEST.getCode())
                        .requestId(requestId)
                        .build())
                .body(RpcHeartbeat.createRequest(requestId))
                .build();
    }

    private RpcMessage buildHeartbeatResponse(RpcMessage requestMessage) {
        long requestId = requestMessage.getHeader().getRequestId();
        return RpcMessage.builder()
                .header(RpcHeader.builder()
                        .serializerType((byte) 0)
                        .messageType(RpcMessageType.HEARTBEAT_RESPONSE.getCode())
                        .requestId(requestId)
                        .build())
                .body(RpcHeartbeat.createResponse(requestId))
                .build();
    }
}
