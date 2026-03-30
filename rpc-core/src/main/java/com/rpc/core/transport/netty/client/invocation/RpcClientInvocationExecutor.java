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

public class RpcClientInvocationExecutor {
    private final RpcServiceResolver serviceResolver;
    private final CircuitBreakerManager circuitBreakerManager;
    private final RetryExecutor retryExecutor;
    private final InvocationOptionsResolver invocationOptionsResolver;
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

    public RpcResponse execute(RpcRequest rpcRequest, RpcTransportInvoker transportInvoker) throws Exception {
        InvocationOptions options = invocationOptionsResolver.resolve(rpcRequest);
        String rateLimitKey = resolveRateLimitKey(rpcRequest, options);
        // 请求真正发出前先做 consumer 侧限流；这属于调用治理，不属于 transport 职责。
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
        // INVOKER 阶段的 filter 位于 cluster/transport 之前，
        // 适合承接熔断、降级、统计这类“围绕一次调用”的横切逻辑。
        FilterContext context = FilterContext.builder()
                .request(rpcRequest)
                .invocationOptions(options)
                .build();

        return (RpcResponse) new DefaultFilterChain(
                FilterManager.getFilters(FilterPhase.INVOKER),
                filterContext -> invokeWithCluster(filterContext.getRequest(), transportInvoker, options)
        ).proceed(context);
    }

    public InetSocketAddress resolveServiceAddress(String serviceName) throws RpcException {
        return serviceResolver.resolve(serviceName);
    }

    private Callable<RpcResponse> invokeOnce(RpcRequest rpcRequest,
                                             RpcTransportInvoker transportInvoker,
                                             InvocationOptions options) {
        return () -> {
            // serviceResolver 只负责“选地址”，不负责真正发请求。
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
            // 这里记录的是实例级成功状态，用于后续按地址维度判断是否可继续放流量。
            instanceCircuitBreaker.recordSuccess();
            return response;
        };
    }

    private String resolveRateLimitKey(RpcRequest rpcRequest, InvocationOptions options) {
        if (options.getCircuitBreakerScope() == CircuitBreakerScope.METHOD) {
            return rpcRequest.getServiceName() + "#" + rpcRequest.getMethodName();
        }
        return rpcRequest.getServiceName();
    }

    private void applyInvocationOptions(RpcRequest rpcRequest, InvocationOptions options) {
        // 方法级治理最终会折叠到 request attachments，
        // 这样后面的 resolver / transport 无需依赖 MethodConfig 本身。
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

    private RpcResponse invokeWithCluster(RpcRequest rpcRequest,
                                          RpcTransportInvoker transportInvoker,
                                          InvocationOptions options) throws Exception {
        // cluster 层负责 fail-fast / fail-over 等容错策略，
        // transport 层只负责把请求发到某个已选中的地址。
        ClusterInvoker clusterInvoker = ClusterInvokerFactory.create(
                options.getClusterStrategy(),
                retryExecutor,
                invokeOnce(rpcRequest, transportInvoker, options),
                options.getRetryTimes()
        );
        return clusterInvoker.invoke(rpcRequest, transportInvoker);
    }
}

