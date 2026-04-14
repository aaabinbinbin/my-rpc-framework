package com.rpc.core.invoke.cluster;

import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.transport.netty.client.invocation.RpcTransportInvoker;

import java.util.concurrent.Callable;

/**
 * fail-fast 集群策略实现。
 *
 * 这种策略的含义很简单：
 * 只尝试一次调用，失败就立刻返回，不做额外重试。
 */
public class FailFastClusterInvoker implements ClusterInvoker {
    /** 单次实际调用动作。 */
    private final Callable<RpcResponse> invocation;

    public FailFastClusterInvoker(Callable<RpcResponse> invocation) {
        this.invocation = invocation;
    }

    /** 直接执行一次调用。 */
    @Override
    public RpcResponse invoke(RpcRequest request, RpcTransportInvoker transportInvoker) throws Exception {
        return invocation.call();
    }
}
