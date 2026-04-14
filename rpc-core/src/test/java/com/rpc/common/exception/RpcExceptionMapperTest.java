package com.rpc.common.exception;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.common.exception.RpcExceptionMapper;
import com.rpc.core.common.exception.dedicated.ClientOverloadedException;
import com.rpc.core.common.exception.dedicated.TimeoutException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：RPC异常映射测试")
class RpcExceptionMapperTest {
    @DisplayName("验证映射传输超时To专用RPC超时异常场景")
    @Test
    void shouldMapTransportTimeoutToDedicatedRpcTimeoutException() {
        Exception mapped = RpcExceptionMapper.fromTransport(new SocketTimeoutException("read timed out"));

        assertInstanceOf(TimeoutException.class, mapped);
        assertEquals(ErrorCode.NETWORK_TIMEOUT, ((TimeoutException) mapped).getErrorCode());
    }

    @DisplayName("验证映射连接拒绝To可重试RPC异常场景")
    @Test
    void shouldMapConnectionRefusedToRetryableRpcException() {
        Exception mapped = RpcExceptionMapper.fromTransport(new ConnectException("Connection refused"));

        assertInstanceOf(RpcException.class, mapped);
        assertEquals(ErrorCode.CONNECTION_REFUSED, ((RpcException) mapped).getErrorCode());
    }

    @DisplayName("验证映射关闭通道To通道不可用场景")
    @Test
    void shouldMapClosedChannelToChannelUnavailable() {
        Exception mapped = RpcExceptionMapper.fromTransport(new ClosedChannelException());

        assertInstanceOf(RpcException.class, mapped);
        assertEquals(ErrorCode.CHANNEL_UNAVAILABLE, ((RpcException) mapped).getErrorCode());
    }

    @DisplayName("验证映射客户端过载To客户端繁忙场景")
    @Test
    void shouldMapClientOverloadToClientBusy() {
        Exception mapped = RpcExceptionMapper.fromTransport(new ClientOverloadedException(
                ClientOverloadedException.Reason.PENDING_REQUEST_LIMIT_EXCEEDED,
                "Too many pending RPC requests"
        ));

        assertInstanceOf(RpcException.class, mapped);
        assertEquals(ErrorCode.CLIENT_BUSY, ((RpcException) mapped).getErrorCode());
    }

    @DisplayName("验证映射服务端繁忙响应码To可重试RPC异常场景")
    @Test
    void shouldMapServerBusyResponseCodeToRetryableRpcException() {
        RpcException mapped = RpcExceptionMapper.fromResponse(
                ErrorCode.SERVER_BUSY.getCode(),
                ErrorCode.SERVER_BUSY.getDescription()
        );

        assertEquals(ErrorCode.SERVER_BUSY, mapped.getErrorCode());
        assertEquals(true, mapped.isRetryable());
    }
}
