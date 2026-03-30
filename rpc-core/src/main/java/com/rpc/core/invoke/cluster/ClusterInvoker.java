package com.rpc.core.invoke.cluster;

import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.transport.netty.client.invocation.RpcTransportInvoker;

public interface ClusterInvoker {
    RpcResponse invoke(RpcRequest request, RpcTransportInvoker transportInvoker) throws Exception;
}

