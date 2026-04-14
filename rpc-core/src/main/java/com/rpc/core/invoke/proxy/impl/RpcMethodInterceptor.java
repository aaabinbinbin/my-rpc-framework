package com.rpc.core.invoke.proxy.impl;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.runtime.DefaultFilterChain;
import com.rpc.core.invoke.filter.context.FilterContext;
import com.rpc.core.invoke.filter.runtime.FilterManager;
import com.rpc.core.invoke.filter.api.FilterPhase;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.transport.RpcTransport;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import java.lang.reflect.Method;

/**
 * 基于 CGLIB 的 RPC 方法拦截器。
 *
 * 所处阶段：consumer 侧代理对象的方法被调用时。
 * 主要职责：把本地方法调用转换成 RpcRequest，执行 consumer 过滤器链，并通过 RpcTransport 发送请求。
 *
 * 注意事项：该类用于类代理场景；接口代理路径由 RpcInvocationHandler 处理。
 */
public class RpcMethodInterceptor implements MethodInterceptor {
    /** 被代理的服务类，用于构造 serviceName。 */
    private final Class<?> serviceClass;
    /** consumer 侧传输客户端。 */
    private final RpcTransport client;

    /**
     * 创建 CGLIB 方法拦截器。
     */
    public RpcMethodInterceptor(Class<?> serviceClass, RpcTransport client) {
        this.serviceClass = serviceClass;
        this.client = client;
    }

    /**
     * 拦截本地方法调用并执行远程 RPC。
     *
     * 边界处理：Object 基础方法走本地 super 调用；client 为空时快速失败；finally 中清理 RpcContext 防止线程复用污染。
     */
    @Override
    public Object intercept(Object o, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return methodProxy.invokeSuper(o, args);
        }

        if (client == null) {
            throw new IllegalStateException("RPC client is not initialized");
        }

        RpcContext rpcContext = RpcContext.create();

        try {
            RpcRequest request = RpcRequest.builder()
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

