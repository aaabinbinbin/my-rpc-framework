package com.rpc.transport.netty.client.invocation;

import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;

import java.net.InetSocketAddress;

@FunctionalInterface
public interface RpcTransportInvoker {
    RpcResponse invoke(RpcRequest rpcRequest, InetSocketAddress address) throws Exception;
}
