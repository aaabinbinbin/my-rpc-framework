package com.rpc.core.invoke.proxy.impl;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.DefaultFilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterManager;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.transport.RpcTransport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.UUID;

public class RpcInvocationHandler implements InvocationHandler {
    private final Class<?> serviceClass;
    private final RpcTransport client;

    public RpcInvocationHandler(Class<?> serviceClass, RpcTransport client) {
        this.serviceClass = serviceClass;
        this.client = client;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        if (client == null) {
            throw new IllegalStateException("RPC client is not initialized");
        }

        RpcContext rpcContext = RpcContext.create()
                .setRequestId(UUID.randomUUID().toString());

        try {
            RpcRequest request = RpcRequest.builder()
                    .requestId(rpcContext.getRequestId())
                    .serviceName(serviceClass.getName())
                    .methodName(method.getName())
                    .parameterTypes(method.getParameterTypes())
                    .parameters(args)
                    .returnType(method.getReturnType())
                    .build();

            FilterContext context = FilterContext.builder()
                    .rpcContext(rpcContext)
                    .request(request)
                    .serviceClass(serviceClass)
                    .build();
            RpcResponse response = (RpcResponse) new DefaultFilterChain(
                    FilterManager.getFilters(FilterPhase.CONSUMER),
                    filterContext -> client.sendRequest(filterContext.getRequest())
            ).proceed(context);

            if (response.getCode() != null && response.getCode() == 200) {
                return response.getData();
            }
            throw new RuntimeException("RPC invoke failed: " + response.getMessage());
        } finally {
            RpcContext.clear();
        }
    }
}

