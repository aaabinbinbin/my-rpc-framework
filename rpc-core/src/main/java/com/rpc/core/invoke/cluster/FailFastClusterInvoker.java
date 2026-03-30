package com.rpc.core.invoke.cluster;

import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.transport.netty.client.invocation.RpcTransportInvoker;

import java.util.concurrent.Callable;

public class FailFastClusterInvoker implements ClusterInvoker {
    private final Callable<RpcResponse> invocation;

    public FailFastClusterInvoker(Callable<RpcResponse> invocation) {
        this.invocation = invocation;
    }

    @Override
    public RpcResponse invoke(RpcRequest request, RpcTransportInvoker transportInvoker) throws Exception {
        return invocation.call();
    }
}

