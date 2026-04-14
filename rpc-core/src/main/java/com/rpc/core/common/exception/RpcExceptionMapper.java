package com.rpc.core.common.exception;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.dedicated.ClientOverloadedException;
import com.rpc.core.common.exception.dedicated.TimeoutException;
import io.netty.channel.ConnectTimeoutException;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;

/**
 * RPC 异常映射器。
 *
 * 所处阶段：网络调用失败、服务端返回错误响应、pending 请求超时等异常需要进入统一容错链时。
 * 主要职责：把 JDK、Netty、框架内部异常映射为 RpcException 或可被上层识别的异常类型。
 *
 * 设计原因：重试、熔断、降级不能直接依赖各类底层异常，否则不同传输实现会产生不同判断结果。
 */
public final class RpcExceptionMapper {
    /** 工具类不允许实例化。 */
    private RpcExceptionMapper() {
    }

    /**
     * 根据服务端响应码构造 RpcException。
     *
     * 边界处理：code 为空或未知时统一映射为 SERVER_ERROR；message 为空时使用 ErrorCode 默认描述。
     */
    public static RpcException fromResponse(Integer code, String message) {
        ErrorCode errorCode = fromCode(code);
        return new RpcException(errorCode, message == null || message.isBlank()
                ? errorCode.getDescription()
                : message);
    }

    /**
     * 把传输层 Throwable 映射为调用链可识别的异常。
     *
     * 注意事项：如果已经是 RpcException 则直接透传，避免丢失原始错误码。
     * 边界处理：非 Exception 的 Throwable 会被包装成 SERVER_ERROR，防止上层签名无法抛出。
     */
    public static Exception fromTransport(Throwable throwable) {
        if (throwable instanceof RpcException rpcException) {
            return rpcException;
        }
        if (throwable instanceof java.util.concurrent.TimeoutException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof ConnectTimeoutException) {
            return new TimeoutException("RPC request timed out", throwable);
        }
        if (throwable instanceof ConnectException) {
            return new RpcException(ErrorCode.CONNECTION_REFUSED, "RPC connection refused", throwable);
        }
        if (throwable instanceof ClosedChannelException) {
            return new RpcException(ErrorCode.CHANNEL_UNAVAILABLE, "RPC channel is unavailable", throwable);
        }
        if (throwable instanceof EOFException) {
            return new RpcException(ErrorCode.CONNECTION_RESET, "RPC connection closed by peer", throwable);
        }
        if (throwable instanceof ClientOverloadedException clientOverloadedException) {
            return new RpcException(ErrorCode.CLIENT_BUSY, clientOverloadedException.getMessage(), clientOverloadedException);
        }
        if (throwable instanceof SocketException socketException) {
            String message = socketException.getMessage();
            if (message != null && message.toLowerCase().contains("reset")) {
                return new RpcException(ErrorCode.CONNECTION_RESET, "RPC connection reset", socketException);
            }
            return new RpcException(ErrorCode.CHANNEL_UNAVAILABLE, "RPC socket is unavailable", socketException);
        }
        if (throwable instanceof IllegalStateException illegalStateException) {
            return new RpcException(ErrorCode.CHANNEL_UNAVAILABLE, illegalStateException.getMessage(), illegalStateException);
        }
        return throwable instanceof Exception exception
                ? exception
                : new RpcException(ErrorCode.SERVER_ERROR, "Unknown rpc invoke error", throwable);
    }

    /**
     * 将响应数字码解析为 ErrorCode。
     *
     * 边界处理：兼容历史成功码 200；其他未知码归为 SERVER_ERROR，避免客户端误判成功。
     */
    private static ErrorCode fromCode(Integer code) {
        if (code == null) {
            return ErrorCode.SERVER_ERROR;
        }
        for (ErrorCode errorCode : ErrorCode.values()) {
            if (errorCode.getCode() == code) {
                return errorCode;
            }
        }
        return code == 200 ? ErrorCode.SUCCESS : ErrorCode.SERVER_ERROR;
    }
}
