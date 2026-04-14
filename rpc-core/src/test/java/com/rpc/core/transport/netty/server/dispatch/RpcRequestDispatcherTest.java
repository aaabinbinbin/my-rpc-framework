package com.rpc.core.transport.netty.server.dispatch;

import com.rpc.core.protocol.message.RpcHeader;
import com.rpc.core.protocol.message.RpcHeartbeat;
import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.protocol.message.RpcMessageType;
import com.rpc.core.runtime.server.ServerLifecycle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：RPC请求分发器测试")
class RpcRequestDispatcherTest {
    @DisplayName("验证回显心跳请求时间戳用于延迟计算场景")
    @Test
    void shouldEchoHeartbeatRequestTimestampForLatencyCalculation() {
        RpcRequestDispatcher dispatcher = new RpcRequestDispatcher(null, new ServerLifecycle());
        long requestId = 1234L;
        long clientSendTimestamp = 5678L;
        RpcMessage request = RpcMessage.builder()
                .header(RpcHeader.builder()
                        .serializerType((byte) 0)
                        .messageType(RpcMessageType.HEARTBEAT_REQUEST.getCode())
                        .requestId(requestId)
                        .build())
                .body(RpcHeartbeat.builder()
                        .requestId(requestId)
                        .timestamp(clientSendTimestamp)
                        .build())
                .build();

        RpcMessage response = dispatcher.process(request);

        RpcHeartbeat heartbeatResponse = assertInstanceOf(RpcHeartbeat.class, response.getBody());
        assertEquals(requestId, heartbeatResponse.getRequestId());
        assertEquals(clientSendTimestamp, heartbeatResponse.getTimestamp());
        assertEquals(RpcMessageType.HEARTBEAT_RESPONSE.getCode(), response.getHeader().getMessageType());
    }
}
