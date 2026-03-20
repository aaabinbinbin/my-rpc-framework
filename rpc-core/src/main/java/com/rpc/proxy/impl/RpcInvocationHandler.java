package com.rpc.proxy.impl;

import com.rpc.transport.netty.client.RpcNettyClient;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * RPC 调用处理器 (JDK 实现动态代理)
 */
@Slf4j
public class RpcInvocationHandler implements InvocationHandler {
    private final Class<?> serviceClass;
    // 保留 host 和 port 用于兼容不注册中心的情况
    private final String host;
    private final int port;
    private static RpcNettyClient client;

    public RpcInvocationHandler(Class<?> serviceClass, String host, int port, RpcNettyClient client) {
        this.serviceClass = serviceClass;
        this.host = host;
        this.port = port;
        this.client = client;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 1. 跳过 Object 类的方法
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }
        // 2. 构建 RPC 请求
        RpcRequest request = new RpcRequest();
        request.setServiceName(serviceClass.getName());
        request.setMethodName(method.getName());
        request.setParameterTypes(method.getParameterTypes());
        request.setParameters(args);
        request.setReturnType(method.getReturnType());
        log.info("准备调用：{}.{}", request.getServiceName(),
                request.getMethodName());
        // 3. 发送远程请求
        if (client == null) {
            throw new IllegalStateException("RPC 客户端未初始化");
        }
        // 如果客户端配置了服务注册中心，host 和 port 将被忽略
        RpcResponse response = client.sendRequest(request, host, port);
        // 4. 返回结果
        if (response.getCode() == 200) {
            return response.getData();
        } else {
            throw new RuntimeException("RPC 调用失败：" + response.getMessage());
        }
    }
}
