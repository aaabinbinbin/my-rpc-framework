package com.rpc.proxy.impl;

import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import com.rpc.transport.RpcTransport;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

@Slf4j
public class RpcInvocationHandler implements InvocationHandler {
    private final Class<?> serviceClass;
    private static RpcTransport client;

    public RpcInvocationHandler(Class<?> serviceClass, RpcTransport client) {
        this.serviceClass = serviceClass;
        RpcInvocationHandler.client = client;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        RpcRequest request = new RpcRequest();
        request.setServiceName(serviceClass.getName());
        request.setMethodName(method.getName());
        request.setParameterTypes(method.getParameterTypes());
        request.setParameters(args);
        request.setReturnType(method.getReturnType());

        log.info("准备调用: {}.{}", request.getServiceName(), request.getMethodName());
        if (client == null) {
            throw new IllegalStateException("RPC 客户端未初始化");
        }

        RpcResponse response = client.sendRequest(request);
        if (response.getCode() == 200) {
            return response.getData();
        }
        throw new RuntimeException("RPC 调用失败: " + response.getMessage());
    }
}
