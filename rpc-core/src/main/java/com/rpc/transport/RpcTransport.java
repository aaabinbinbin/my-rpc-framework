package com.rpc.transport;

import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;

public interface RpcTransport extends AutoCloseable {
    RpcResponse sendRequest(RpcRequest rpcRequest) throws Exception;

    void sendRequestAsync(RpcRequest rpcRequest, long requestId) throws Exception;

    @Override
    void close();
}
