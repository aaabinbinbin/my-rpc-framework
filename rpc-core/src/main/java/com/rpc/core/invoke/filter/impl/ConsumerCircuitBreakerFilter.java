package com.rpc.core.invoke.filter.impl;

import com.rpc.core.common.exception.RpcException;
import com.rpc.core.resilience.CircuitBreaker;
import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.invoke.filter.FilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.FilterRuntimeConfig;
import com.rpc.core.invoke.filter.RpcFilter;
import com.rpc.core.invoke.invocation.CircuitBreakerScope;
import com.rpc.core.invoke.invocation.InvocationOptions;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;

/**
 * consumer 侧熔断 / 降级过滤器。
 *
 * 它运行在 invoker 阶段，位置比 consumer 入口更靠后，
 * 更适合围绕“一次完整调用”做熔断判断和降级兜底。
 */
public class ConsumerCircuitBreakerFilter implements RpcFilter {
    @Override
    public FilterPhase phase() {
        return FilterPhase.INVOKER;
    }

    @Override
    public int order() {
        return 0;
    }

    /**
     * 执行熔断判断。
     *
     * 如果当前 breaker 已经不允许继续请求，或者失败次数超过阈值，
     * 就直接走降级逻辑；否则继续执行后续调用链，并根据结果更新 breaker 状态。
     */
    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        RpcRequest request = context.getRequest();
        InvocationOptions options = context.getInvocationOptions();
        String breakerKey = resolveCircuitBreakerKey(request, options);
        CircuitBreaker circuitBreaker = FilterRuntimeConfig.getCircuitBreakerManager()
                .getServiceCircuitBreaker(breakerKey);

        if (shouldDegrade(circuitBreaker, breakerKey)) {
            return applyDegradation(request);
        }

        try {
            Object result = chain.proceed(context);
            circuitBreaker.recordSuccess();
            FilterRuntimeConfig.resetConsumerFailure(breakerKey);
            return result;
        } catch (RpcException e) {
            circuitBreaker.recordFailure();
            FilterRuntimeConfig.incrementConsumerFailure(breakerKey);
            throw e;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            FilterRuntimeConfig.incrementConsumerFailure(breakerKey);
            throw e;
        }
    }

    /**
     * 判断当前调用是否应该直接降级。
     *
     * 当前支持两类触发条件：
     * 1. 熔断器已经打开。
     * 2. 最近失败次数达到阈值。
     */
    private boolean shouldDegrade(CircuitBreaker circuitBreaker, String breakerKey) {
        if (!FilterRuntimeConfig.isConsumerDegradationEnabled()) {
            return false;
        }
        if (!circuitBreaker.allowRequest()) {
            return true;
        }
        return FilterRuntimeConfig.getConsumerFailureCount(breakerKey)
                >= FilterRuntimeConfig.getConsumerFailureThreshold();
    }

    /** 执行降级策略，没有配置策略时抛出 SERVICE_DEGRADED 异常。 */
    private RpcResponse applyDegradation(RpcRequest request) throws RpcException {
        DegradationPolicy degradationPolicy = FilterRuntimeConfig.getConsumerDegradationPolicy();
        if (degradationPolicy != null) {
            return degradationPolicy.degrade(request, new RuntimeException("Service degraded"));
        }
        throw new RpcException(com.rpc.core.common.constant.ErrorCode.SERVICE_DEGRADED, "Service degraded but no policy configured");
    }

    /**
     * 计算熔断 key。
     *
     * 支持按服务维度和按方法维度两种粒度。
     */
    private String resolveCircuitBreakerKey(RpcRequest request, InvocationOptions options) {
        if (options != null && options.getCircuitBreakerScope() == CircuitBreakerScope.METHOD) {
            return request.getServiceName() + "#" + request.getMethodName();
        }
        return request.getServiceName();
    }
}
