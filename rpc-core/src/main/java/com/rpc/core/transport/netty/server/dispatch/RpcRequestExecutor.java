package com.rpc.core.transport.netty.server.dispatch;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.DefaultFilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterManager;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.impl.TraceFilter;
import com.rpc.core.observability.metrics.ServiceMetricsManager;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.runtime.server.ServerLifecycle;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

/**
 * provider 侧业务请求执行器。
 *
 * 如果说 RpcRequestDispatcher 负责“入口分流”，
 * 那这个类负责“真正执行业务请求”。
 *
 * 主要职责：
 * 1. 把请求放到业务线程池执行，避免 IO 线程直接跑用户代码。
 * 2. 根据 serviceName 从本地注册表找到服务对象。
 * 3. 恢复 provider 侧 RpcContext 和 trace 信息。
 * 4. 经过 provider 过滤器链。
 * 5. 最终通过反射调用目标方法并封装成 RpcResponse。
 */
public class RpcRequestExecutor {
    /** provider 本地注册表，用于根据服务名找到真实服务对象。 */
    private final LocalRegistry localRegistry;

    /** 服务级指标管理器，供 provider 侧观测和统计使用。 */
    private final ServiceMetricsManager metricsManager;

    /** 业务线程池，真正的业务调用在这里执行。 */
    private final ExecutorService bizExecutor;

    /** 服务端生命周期状态，用于优雅停机时统计 inflight 请求。 */
    private final ServerLifecycle serverLifecycle;

    public RpcRequestExecutor(LocalRegistry localRegistry,
                              ExecutorService bizExecutor,
                              ServerLifecycle serverLifecycle) {
        this.localRegistry = localRegistry;
        this.metricsManager = ServiceMetricsManager.getInstance();
        this.bizExecutor = bizExecutor;
        this.serverLifecycle = serverLifecycle;
    }

    /**
     * 执行一次业务请求。
     *
     * 这里先增加 inflight 计数，表示当前有一个请求正在处理中；
     * 然后把真正执行逻辑提交到业务线程池，最后再减少 inflight 计数。
     */
    public RpcResponse execute(RpcRequest rpcRequest) {
        String serviceName = rpcRequest.getServiceName();
        serverLifecycle.incrementInflight();

        try {
            return bizExecutor.submit(() -> invoke(rpcRequest)).get();
        } catch (Exception e) {
            return RpcResponse.fail(500, e.getMessage(), rpcRequest.getRequestId());
        } finally {
            serverLifecycle.decrementInflight();
        }
    }

    /**
     * 在业务线程池中真正执行请求。
     * 这里会恢复 provider 侧上下文，再经过 provider filter，
     * 最后把执行结果统一包装成 RpcResponse。
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
     * 通过反射调用 provider 本地服务对象的方法。
     *
     * 这是 provider 执行链最终落到业务实现类的地方。
     */
    private Object invokeTarget(Object serviceBean, RpcRequest rpcRequest) throws Exception {
        Method method = serviceBean.getClass().getMethod(
                rpcRequest.getMethodName(),
                rpcRequest.getParameterTypes()
        );
        return method.invoke(serviceBean, rpcRequest.getParameters());
    }
}
