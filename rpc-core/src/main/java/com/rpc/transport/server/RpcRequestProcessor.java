package com.rpc.transport.server;

import com.rpc.protocol.RpcMessage;

public interface RpcRequestProcessor {
    RpcMessage process(RpcMessage message);
}
