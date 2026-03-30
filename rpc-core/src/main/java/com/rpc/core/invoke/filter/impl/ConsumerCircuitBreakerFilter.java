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

public class ConsumerCircuitBreakerFilter implements RpcFilter {
    @Override
    public FilterPhase phase() {
        return FilterPhase.INVOKER;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        RpcRequest request = context.getRequest();
        InvocationOptions options = context.getInvocationOptions();
        // 熔断 key 可以是服务级，也可以是方法级，
        // 具体取决于当前调用解析出来的 InvocationOptions。
        String breakerKey = resolveCircuitBreakerKey(request, options);
        CircuitBreaker circuitBreaker = FilterRuntimeConfig.getCircuitBreakerManager()
                .getServiceCircuitBreaker(breakerKey);

        if (shouldDegrade(circuitBreaker, breakerKey)) {
            return applyDegradation(request);
        }

        try {
            Object result = chain.proceed(context);
            // 调用成功后，同时重置熔断器状态和轻量失败计数，
            // 后者用于降级阈值这条快捷判断路径。
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

    private boolean shouldDegrade(CircuitBreaker circuitBreaker, String breakerKey) {
        if (!FilterRuntimeConfig.isConsumerDegradationEnabled()) {
            return false;
        }
        // 当前支持两种降级触发条件：
        // 1. 熔断器已经处于打开状态
        // 2. 最近失败次数达到配置的降级阈值
        if (!circuitBreaker.allowRequest()) {
            return true;
        }
        return FilterRuntimeConfig.getConsumerFailureCount(breakerKey)
                >= FilterRuntimeConfig.getConsumerFailureThreshold();
    }

    private RpcResponse applyDegradation(RpcRequest request) throws RpcException {
        DegradationPolicy degradationPolicy = FilterRuntimeConfig.getConsumerDegradationPolicy();
        if (degradationPolicy != null) {
            return degradationPolicy.degrade(request, new RuntimeException("Service degraded"));
        }
        throw new RpcException(com.rpc.core.common.constant.ErrorCode.SERVICE_DEGRADED, "Service degraded but no policy configured");
    }

    private String resolveCircuitBreakerKey(RpcRequest request, InvocationOptions options) {
        if (options != null && options.getCircuitBreakerScope() == CircuitBreakerScope.METHOD) {
            return request.getServiceName() + "#" + request.getMethodName();
        }
        // 默认走服务级保护，这样即使没有方法级覆盖，行为也保持稳定一致。
        return request.getServiceName();
    }
}

