package com.rpc.faulttolerance.degrade;

import com.rpc.common.constant.ErrorCode;
import com.rpc.faulttolerance.DegradationPolicy;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 快速失败降级策略
 * 立即返回错误，不等待
 */
@Slf4j
public class FailFastDegradation implements DegradationPolicy {

    @Override
    public RpcResponse degrade(RpcRequest request, Throwable cause) {
        log.warn("执行快速失败降级：{}.{}",
                request.getServiceName(), request.getMethodName());

        return RpcResponse.fail(
                ErrorCode.SERVICE_DEGRADED.getCode(),
                "服务已降级：" + cause.getMessage(),
                request.getRequestId()
        );
    }
}
