package com.rpc.core.common.exception.dedicated;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;

public class CircuitBreakerException extends RpcException {
    public CircuitBreakerException(String serviceName) {
        super(
                ErrorCode.CIRCUIT_BREAKER_OPEN,
                "Circuit breaker is open for service [" + serviceName + "]"
        );
    }
}
