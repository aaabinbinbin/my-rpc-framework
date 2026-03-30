package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.filter.FilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.RpcFilter;
import com.rpc.core.observability.metrics.ServiceMetrics;
import com.rpc.core.observability.metrics.ServiceMetricsManager;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProviderMetricsFilter implements RpcFilter {
    @Override
    public FilterPhase phase() {
        return FilterPhase.PROVIDER;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        long start = System.nanoTime();
        try {
            Object result = chain.proceed(context);
            record(context, System.nanoTime() - start, false);
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

    private void record(FilterContext context, long latencyNanos, boolean failed) {
        // Provider 侧沿用同一套服务级指标模型，
        // 这样 consumer/provider 两边的观测数据可以按相同维度对齐比较。
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
}

