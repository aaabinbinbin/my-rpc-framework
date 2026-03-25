package com.rpc.common.exception;

import com.rpc.common.constant.ErrorCode;
import lombok.Getter;

/**
 * RPC 异常基类
 * 所有 RPC 相关异常都应继承此类
 */
@Getter
public class RpcException extends Exception{
    /** 错误码 */
    private final ErrorCode errorCode;

    /** 是否可重试 */
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
