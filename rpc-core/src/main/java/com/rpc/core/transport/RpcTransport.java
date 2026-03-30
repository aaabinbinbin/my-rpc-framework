package com.rpc.core.transport;

import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;

public interface RpcTransport extends AutoCloseable {
    RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception;

    void sendRequestAsync(RpcRequest rpcRequest, long requestId) throws Exception;

    @Override
    void close();
}

