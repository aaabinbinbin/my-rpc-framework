package com.rpc.core.invoke.filter.impl;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.invoke.filter.api.FilterChain;
import com.rpc.core.invoke.filter.context.FilterContext;
import com.rpc.core.invoke.filter.api.FilterPhase;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfig;
import com.rpc.core.invoke.filter.api.RpcFilter;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;

/**
 * provider 侧限流过滤器。
 *
 * 它运行在 provider 业务执行之前，
 * 用于保护热点服务 / 热点方法不被过量流量压垮。
 */
public class ProviderRateLimitFilter implements RpcFilter {
    @Override
    public FilterPhase phase() {
        return FilterPhase.PROVIDER;
    }

    @Override
    public int order() {
        return 5;
    }

    /**
     * 按 service#method 维度做 provider 侧限流。
     * 如果开启了 provider 降级，也可以把限流失败转换为更平滑的降级返回，
     * 而不一定是直接报错。
     */
    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        RpcRequest request = context.getRequest();
        String key = request.getServiceName() + "#" + request.getMethodName();
        if (!FilterRuntimeConfig.tryAcquireProvider(key)) {
            if (FilterRuntimeConfig.isProviderDegradationEnabled()) {
                DegradationPolicy degradationPolicy = FilterRuntimeConfig.getProviderDegradationPolicy();
                if (degradationPolicy != null) {
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
