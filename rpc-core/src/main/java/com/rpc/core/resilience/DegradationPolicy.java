package com.rpc.core.resilience;

import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;

/**
 * 请求被拒绝或被短路时使用的降级策略。
 */
public interface DegradationPolicy {
    RpcResponse degrade(RpcRequest request, Throwable cause);
}
