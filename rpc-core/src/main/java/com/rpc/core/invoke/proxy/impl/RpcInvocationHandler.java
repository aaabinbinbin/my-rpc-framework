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

/**
 * JDK 动态代理的调用处理器。
 *
 * 这是 consumer 侧最关键的入口类之一。
 * 业务代码看起来像是在调用本地接口方法，
 * 但真正的方法调用会先进入这个 handler，
 * 再被翻译成 RpcRequest 并交给后续调用链处理。
 */
public class RpcInvocationHandler implements InvocationHandler {
    /** 当前代理对应的服务接口类型，例如 HelloService。 */
    private final Class<?> serviceClass;

    /** 传输层客户端，用于把 RpcRequest 发到远端。 */
    private final RpcTransport client;

    public RpcInvocationHandler(Class<?> serviceClass, RpcTransport client) {
        this.serviceClass = serviceClass;
        this.client = client;
    }

    /**
     * 接管代理对象上的所有方法调用。
     *
     * 处理流程可以概括为：
     * 1. 过滤 Object 自带方法，避免把 toString/equals 等当成 RPC 调用。
     * 2. 创建 RpcContext，生成本次调用的 requestId。
     * 3. 把本地方法调用翻译成 RpcRequest。
     * 4. 经过 consumer 阶段过滤器链。
     * 5. 交给传输层继续执行真正的远程调用。
     */
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
            // 每次调用结束后都清理线程上下文，避免线程复用时串请求。
            RpcContext.clear();
        }
    }
}
