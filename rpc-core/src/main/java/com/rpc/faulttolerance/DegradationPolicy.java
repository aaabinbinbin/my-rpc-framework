package com.rpc.faulttolerance;

import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;

/**
 * 降级策略接口
 * 当服务不可用时提供兜底方案
 */
public interface DegradationPolicy {

    /**
     * 执行降级逻辑
     * @param request 原始请求
     * @param cause 导致降级的原因
     * @return 降级响应
     */
    RpcResponse degrade(RpcRequest request, Throwable cause);
}
