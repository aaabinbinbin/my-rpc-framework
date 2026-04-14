package com.rpc.core.common.exception.dedicated;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;

/**
 * 超时异常
 * 专门用于处理各种超时场景。
 */
public class TimeoutException extends RpcException {
    public TimeoutException(String message) {
        super(ErrorCode.NETWORK_TIMEOUT, message);
    }

    public TimeoutException(String message, Throwable cause) {
        super(ErrorCode.NETWORK_TIMEOUT, message, cause);
    }
}

