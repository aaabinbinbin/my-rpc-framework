package com.rpc.core.transport.netty.server.dispatch;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.runtime.DefaultFilterChain;
import com.rpc.core.invoke.filter.context.FilterContext;
import com.rpc.core.invoke.filter.runtime.FilterManager;
import com.rpc.core.invoke.filter.api.FilterPhase;
import com.rpc.core.invoke.filter.impl.TraceFilter;
import com.rpc.core.observability.metrics.ServiceMetricsManager;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.runtime.server.ServerLifecycle;

import java.lang.reflect.Method;

/**
 * provider 侧业务请求执行器。
 *
 * 所处阶段：请求已经通过 Netty 解码、消息类型分发和业务线程池隔离，
 * 当前类负责进入 provider 本地执行链。
 *
 * 主要职责：
 * - 根据 serviceName 从 LocalRegistry 找到本地服务实现对象。
 * - 构造 provider 侧 RpcContext，承接 requestId、traceId 和 attachments。
 * - 执行 provider 阶段过滤器链，例如 MDC、metrics、限流。
 * - 过滤器链放行后，通过反射调用目标服务方法。
 *
 * 注意事项：
 * - 这里返回的是 RpcResponse，异常会转换成失败响应，避免 consumer 一直等待。
 * - RpcContext 基于线程上下文，finally 中必须清理，避免业务线程复用导致上下文串请求。
 */
public class RpcRequestExecutor {
    /** provider 进程内服务注册表，保存 serviceName 到服务实现对象的映射。 */
    private final LocalRegistry localRegistry;
    /** 服务指标管理器；当前类持有它主要用于确保服务维度 metrics 已初始化。 */
    private final ServiceMetricsManager metricsManager;
    /** 服务端生命周期状态，用于统计 inflight 请求并支持优雅停机。 */
    private final ServerLifecycle serverLifecycle;

    public RpcRequestExecutor(LocalRegistry localRegistry,
                              ServerLifecycle serverLifecycle) {
        this.localRegistry = localRegistry;
        this.metricsManager = ServiceMetricsManager.getInstance();
        this.serverLifecycle = serverLifecycle;
    }

    /**
     * 执行一次 provider 侧业务请求。
     *
     * inflight 统计覆盖本地执行全过程，用于优雅停机时等待在途请求 drain。
     */
    public RpcResponse execute(RpcRequest rpcRequest) {
        serverLifecycle.incrementInflight();
        try {
            return invoke(rpcRequest);
        } catch (Exception e) {
            return RpcResponse.fail(500, e.getMessage(), rpcRequest.getRequestId());
        } finally {
            serverLifecycle.decrementInflight();
        }
    }

    /**
     * 进入 provider 本地调用链。
     *
     * 这里先把请求元数据写入 RpcContext，再通过 provider filter 链处理横切逻辑；
     * 真正的反射调用被放在 filter 链末端，便于限流、MDC、metrics 等逻辑统一包裹业务执行。
     */
    private RpcResponse invoke(RpcRequest rpcRequest) throws Exception {
        String serviceName = rpcRequest.getServiceName();
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

    /**
     * 反射调用目标服务方法。
     *
     * 方法定位依赖 methodName + parameterTypes，原因是 Java 支持方法重载；
     * 只用方法名无法唯一定位目标方法。
     */
    private Object invokeTarget(Object serviceBean, RpcRequest rpcRequest) throws Exception {
        Method method = serviceBean.getClass().getMethod(
                rpcRequest.getMethodName(),
                rpcRequest.getParameterTypes()
        );
        return method.invoke(serviceBean, rpcRequest.getParameters());
    }
}
