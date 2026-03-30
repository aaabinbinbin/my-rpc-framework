package com.rpc.core.resilience.degrade;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.resilience.DegradationPolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * 立即返回降级响应的降级策略。
 */
@Slf4j
public class FailFastDegradation implements DegradationPolicy {
    @Override
    public RpcResponse degrade(RpcRequest request, Throwable cause) {
        log.warn("Fail-fast degradation triggered for {}.{}",
                request.getServiceName(), request.getMethodName());

        return RpcResponse.fail(
                ErrorCode.SERVICE_DEGRADED.getCode(),
                "Service degraded: " + cause.getMessage(),
                request.getRequestId()
        );
    }
}
