package com.rpc.core.common.exception;

import com.rpc.core.common.constant.ErrorCode;
import lombok.Getter;

/**
 * RPC 操作使用的框架级受检异常。
 */
@Getter
public class RpcException extends Exception {
    private final ErrorCode errorCode;
    private final boolean retryable;

    public RpcException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = errorCode.isRetryable();
    }

    public RpcException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = errorCode.isRetryable();
    }
}
