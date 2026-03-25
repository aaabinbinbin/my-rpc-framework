package com.rpc.common.exception.dedicated;

import com.rpc.common.constant.ErrorCode;
import com.rpc.common.exception.RpcException;

/**
 * 熔断器异常
 * 当熔断器打开时抛出此异常
 */
public class CircuitBreakerException extends RpcException {
    public CircuitBreakerException(String serviceName) {
        super(ErrorCode.CIRCUIT_BREAKER_OPEN,
                "服务 [" + serviceName + "] 熔断器已打开，拒绝访问");
    }
}
