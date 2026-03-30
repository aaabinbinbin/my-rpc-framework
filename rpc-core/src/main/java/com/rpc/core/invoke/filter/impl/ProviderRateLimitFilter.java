package com.rpc.core.invoke.filter.impl;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.invoke.filter.FilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.FilterRuntimeConfig;
import com.rpc.core.invoke.filter.RpcFilter;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;

public class ProviderRateLimitFilter implements RpcFilter {
    @Override
    public FilterPhase phase() {
        return FilterPhase.PROVIDER;
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        RpcRequest request = context.getRequest();
        // Provider 侧限流按 service#method 维度生效，
        // 这样可以单独保护热点方法，而不是把整个服务的所有方法一起限住。
        String key = request.getServiceName() + "#" + request.getMethodName();
        if (!FilterRuntimeConfig.tryAcquireProvider(key)) {
            if (FilterRuntimeConfig.isProviderDegradationEnabled()) {
                DegradationPolicy degradationPolicy = FilterRuntimeConfig.getProviderDegradationPolicy();
                if (degradationPolicy != null) {
        // 开启 provider 降级后，限流不一定直接硬拒绝，
        // 也可以转成更平滑的降级返回。
                    return degradationPolicy.degrade(request, new RuntimeException("Provider rate limited"));
                }
            }
            return RpcResponse.fail(
                    ErrorCode.RATE_LIMIT_EXCEEDED.getCode(),
                    ErrorCode.RATE_LIMIT_EXCEEDED.getDescription(),
                    request.getRequestId()
            );
        }
        return chain.proceed(context);
    }
}

