package com.rpc.core.invoke.invocation;

import com.rpc.core.protocol.RpcRequest;

public interface InvocationOptionsResolver {
    InvocationOptions resolve(RpcRequest request);
}

