package com.rpc.core.transport.netty.client.invocation;

import com.rpc.core.invoke.cluster.ClusterInvoker;
import com.rpc.core.invoke.cluster.ClusterInvokerFactory;
import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.resilience.CircuitBreaker;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.resilience.ratelimit.RateLimiterManager;
import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.invoke.filter.DefaultFilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterManager;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.invocation.InvocationAttachmentKeys;
import com.rpc.core.invoke.invocation.InvocationOptions;
import com.rpc.core.invoke.invocation.InvocationOptionsResolver;
import com.rpc.core.invoke.invocation.CircuitBreakerScope;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;

import java.net.InetSocketAddress;
import java.util.concurrent.Callable;

/**
 * consumer 侧调用编排器。
 *
 * 这个类位于“代理入口”和“底层 transport 发送”之间，
 * 是一次远程调用真正进入执行前的编排中心。
 *
 * 主要职责包括：
 * 1. 解析方法级调用配置。
 * 2. 在发送前执行限流等治理逻辑。
 * 3. 运行 invoker 阶段过滤器链。
 * 4. 调用服务解析器选择 provider 地址。
 * 5. 结合集群策略、重试器和熔断器完成一次调用。
 */
public class RpcClientInvocationExecutor {
    /** 服务解析器，负责从服务目录中选择本次实际调用的 provider 地址。 */
    private final RpcServiceResolver serviceResolver;

    /** 熔断器管理器，负责维护服务级和实例级熔断状态。 */
    private final CircuitBreakerManager circuitBreakerManager;

    /** 请求级重试执行器。 */
    private final RetryExecutor retryExecutor;

    /** 方法级配置解析器，把 MethodConfig 等配置折叠成 InvocationOptions。 */
    private final InvocationOptionsResolver invocationOptionsResolver;

    /** 限流管理器，负责在请求真正发出前做令牌校验。 */
    private final RateLimiterManager rateLimiterManager;

    public RpcClientInvocationExecutor(RpcServiceResolver serviceResolver,
                                       CircuitBreakerManager circuitBreakerManager,
                                       RetryExecutor retryExecutor,
                                       InvocationOptionsResolver invocationOptionsResolver,
                                       RateLimiterManager rateLimiterManager) {
        this.serviceResolver = serviceResolver;
        this.circuitBreakerManager = circuitBreakerManager;
        this.retryExecutor = retryExecutor;
        this.invocationOptionsResolver = invocationOptionsResolver;
        this.rateLimiterManager = rateLimiterManager;
    }

    /**
     * 执行一次远程调用。
     *
     * 这里并不直接操作网络，而是先把本次调用所需的治理逻辑和策略都组织好，
     * 然后再把真正的发送动作委托给 transportInvoker。
     */
    public RpcResponse execute(RpcRequest rpcRequest, RpcTransportInvoker transportInvoker) throws Exception {
        InvocationOptions options = invocationOptionsResolver.resolve(rpcRequest);
        String rateLimitKey = resolveRateLimitKey(rpcRequest, options);
        if (rateLimiterManager != null
                && options.isRateLimitEnabled()
                && !rateLimiterManager.tryAcquire(rateLimitKey, options.getRateLimitPermitsPerSecond())) {
            return RpcResponse.fail(
                    ErrorCode.RATE_LIMIT_EXCEEDED.getCode(),
                    ErrorCode.RATE_LIMIT_EXCEEDED.getDescription(),
                    rpcRequest.getRequestId()
            );
        }
        applyInvocationOptions(rpcRequest, options);
        FilterContext context = FilterContext.builder()
                .request(rpcRequest)
                .invocationOptions(options)
                .build();

        return (RpcResponse) new DefaultFilterChain(
                FilterManager.getFilters(FilterPhase.INVOKER),
                filterContext -> invokeWithCluster(filterContext.getRequest(), transportInvoker, options)
        ).proceed(context);
    }

    /**
     * 给异步发送等场景提供单独的服务地址解析能力。
     *
     * 这个方法只负责选地址，不负责真正发送请求。
     */
    public InetSocketAddress resolveServiceAddress(String serviceName) throws RpcException {
        return serviceResolver.resolve(serviceName);
    }

    /**
     * 构造“一次真正调用 provider”的动作。
     *
     * 这里会先通过 serviceResolver 选出实际地址，再委托 transportInvoker 发起调用。
     * 如果调用成功，还会记录实例级熔断器的成功状态。
     */
    private Callable<RpcResponse> invokeOnce(RpcRequest rpcRequest,
                                             RpcTransportInvoker transportInvoker,
                                             InvocationOptions options) {
        return () -> {
            InetSocketAddress address = serviceResolver.resolve(
                    rpcRequest.getServiceName(),
                    options.getLoadBalancerName()
            );
            RpcResponse response = transportInvoker.invoke(rpcRequest, address);
            if (response.getCode() == null || response.getCode() != 200) {
                throw new RpcException(ErrorCode.SERVICE_EXCEPTION, "RPC invoke failed: " + response.getMessage());
            }

            CircuitBreaker instanceCircuitBreaker = circuitBreakerManager.getInstanceCircuitBreaker(
                    rpcRequest.getServiceName(), address);
            instanceCircuitBreaker.recordSuccess();
            return response;
        };
    }

    /**
     * 生成限流 key。
     *
     * 如果熔断 / 限流粒度是方法级，则按“服务#方法”区分；
     * 否则按服务维度限流即可。
     */
    private String resolveRateLimitKey(RpcRequest rpcRequest, InvocationOptions options) {
        if (options.getCircuitBreakerScope() == CircuitBreakerScope.METHOD) {
            return rpcRequest.getServiceName() + "#" + rpcRequest.getMethodName();
        }
        return rpcRequest.getServiceName();
    }

    /**
     * 把方法级配置解析结果写入 request attachments。
     *
     * 这样后面的 resolver、transport、protocol 层就不必直接依赖高层 MethodConfig，
     * 只需要读取请求附件中的标准化结果即可。
     */
    private void applyInvocationOptions(RpcRequest rpcRequest, InvocationOptions options) {
        if (options.getReadTimeout() != null) {
            rpcRequest.getAttachments().put(InvocationAttachmentKeys.READ_TIMEOUT, String.valueOf(options.getReadTimeout()));
        }
        if (options.getSerializerName() != null && !options.getSerializerName().isBlank()) {
            rpcRequest.getAttachments().put(InvocationAttachmentKeys.SERIALIZER, options.getSerializerName());
        }
        if (options.getLoadBalancerName() != null && !options.getLoadBalancerName().isBlank()) {
            rpcRequest.getAttachments().put(InvocationAttachmentKeys.LOAD_BALANCER, options.getLoadBalancerName());
        }
    }

    /**
     * 通过 cluster 层执行一次调用。
     *
     * cluster 负责决定失败时是否重试、是否切换实例等容错策略；
     * transport 层只负责把请求发到已经选中的具体地址。
     */
    private RpcResponse invokeWithCluster(RpcRequest rpcRequest,
                                          RpcTransportInvoker transportInvoker,
                                          InvocationOptions options) throws Exception {
        ClusterInvoker clusterInvoker = ClusterInvokerFactory.create(
                options.getClusterStrategy(),
                retryExecutor,
                invokeOnce(rpcRequest, transportInvoker, options),
                options.getRetryTimes()
        );
        return clusterInvoker.invoke(rpcRequest, transportInvoker);
    }
}
