package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.filter.api.FilterChain;
import com.rpc.core.invoke.filter.context.FilterContext;
import com.rpc.core.invoke.filter.api.FilterPhase;
import com.rpc.core.invoke.filter.api.RpcFilter;
import com.rpc.core.observability.metrics.ServiceMetrics;
import com.rpc.core.observability.metrics.ServiceMetricsManager;
import com.rpc.core.protocol.message.RpcResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * provider 侧指标过滤器。
 *
 * 用于记录服务端视角下的调用耗时和成功率，
 * 便于和 consumer 侧指标一起做端到端对比。
 */
@Slf4j
public class ProviderMetricsFilter implements RpcFilter {
    private static final int SUCCESS_CODE = 200;

    @Override
    public FilterPhase phase() {
        return FilterPhase.PROVIDER;
    }

    @Override
    public int order() {
        return 1;
    }

    /** 记录 provider 侧一次调用的耗时与成败。 */
    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        long start = System.nanoTime();
        try {
            Object result = chain.proceed(context);
            record(context, System.nanoTime() - start, isFailedResponse(result));
            return result;
        } catch (Exception e) {
            record(context, System.nanoTime() - start, true);
            throw e;
        } finally {
            log.debug("RPC provider invoke cost={}us, service={}.{}",
                    (System.nanoTime() - start) / 1000,
                    context.getRequest().getServiceName(),
                    context.getRequest().getMethodName());
        }
    }

    /** provider 侧指标依然按服务维度聚合。 */
    private void record(FilterContext context, long latencyNanos, boolean failed) {
        ServiceMetrics metrics = ServiceMetricsManager.getInstance().get(context.getRequest().getServiceName());
        if (metrics == null) {
            return;
        }
        if (failed) {
            metrics.recordFailure(latencyNanos);
        } else {
            metrics.recordSuccess(latencyNanos);
        }
    }

    private boolean isFailedResponse(Object result) {
        if (!(result instanceof RpcResponse response)) {
            return false;
        }
        // provider 侧不能只统计异常，限流/繁忙/降级等非 200 响应也必须计为失败。
        return response.getCode() == null || response.getCode() != SUCCESS_CODE;
    }
}
