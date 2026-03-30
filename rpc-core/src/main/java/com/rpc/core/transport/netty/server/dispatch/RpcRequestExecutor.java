package com.rpc.core.transport.netty.server.dispatch;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.DefaultFilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterManager;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.impl.TraceFilter;
import com.rpc.core.observability.metrics.ServiceMetrics;
import com.rpc.core.observability.metrics.ServiceMetricsManager;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.runtime.server.ServerLifecycle;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

public class RpcRequestExecutor {
    private final LocalRegistry localRegistry;
    private final ServiceMetricsManager metricsManager;
    private final ExecutorService bizExecutor;
    private final ServerLifecycle serverLifecycle;

    public RpcRequestExecutor(LocalRegistry localRegistry,
                              ExecutorService bizExecutor,
                              ServerLifecycle serverLifecycle) {
        this.localRegistry = localRegistry;
        this.metricsManager = ServiceMetricsManager.getInstance();
        this.bizExecutor = bizExecutor;
        this.serverLifecycle = serverLifecycle;
    }

    public RpcResponse execute(RpcRequest rpcRequest) {
        String serviceName = rpcRequest.getServiceName();
        // inflight 计数用于优雅停机时判断还有多少业务请求没处理完。
        serverLifecycle.incrementInflight();

        try {
            // 真正的业务方法调用放到 bizExecutor，避免 IO 线程直接执行用户代码。
            return bizExecutor.submit(() -> invoke(rpcRequest)).get();
        } catch (Exception e) {
            return RpcResponse.fail(500, e.getMessage(), rpcRequest.getRequestId());
        } finally {
            serverLifecycle.decrementInflight();
        }
    }

    private RpcResponse invoke(RpcRequest rpcRequest) throws Exception {
        String serviceName = rpcRequest.getServiceName();
        // provider 侧先根据请求附件恢复 RpcContext，后续 filter/日志/trace 都从这里取。
        RpcContext rpcContext = RpcContext.create()
                .setRequestId(rpcRequest.getRequestId())
                .setTraceId(rpcRequest.getAttachments().get(TraceFilter.TRACE_ID));
        rpcRequest.getAttachments().forEach(rpcContext::putAttachment);

        try {
            Object serviceBean = localRegistry.getService(serviceName);
            FilterContext context = FilterContext.builder()
                    .rpcContext(rpcContext)
                    .request(rpcRequest)
                    .serviceBean(serviceBean)
                    .serviceClass(serviceBean.getClass())
                    .build();

            // provider filter 链位于真正反射调用之前，适合鉴权、限流、统计、MDC 等横切逻辑。
            Object result = new DefaultFilterChain(
                    FilterManager.getFilters(FilterPhase.PROVIDER),
                    filterContext -> invokeTarget(filterContext.getServiceBean(), filterContext.getRequest())
            ).proceed(context);
            if (result instanceof RpcResponse rpcResponse) {
                return rpcResponse;
            }
            return RpcResponse.success(result, rpcRequest.getRequestId());
        } finally {
            RpcContext.clear();
        }
    }

    private Object invokeTarget(Object serviceBean, RpcRequest rpcRequest) throws Exception {
        Method method = serviceBean.getClass().getMethod(
                rpcRequest.getMethodName(),
                rpcRequest.getParameterTypes()
        );
        return method.invoke(serviceBean, rpcRequest.getParameters());
    }
}

