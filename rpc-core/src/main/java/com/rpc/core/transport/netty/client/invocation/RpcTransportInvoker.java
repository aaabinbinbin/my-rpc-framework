package com.rpc.core.transport.netty.client.invocation;

import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;

import java.net.InetSocketAddress;

@FunctionalInterface
public interface RpcTransportInvoker {
    RpcResponse invoke(RpcRequest rpcRequest, InetSocketAddress address) throws Exception;
}

