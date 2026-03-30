package com.rpc.core.transport.server;

import com.rpc.core.protocol.RpcMessage;

public interface RpcRequestProcessor {
    RpcMessage process(RpcMessage message);
}

