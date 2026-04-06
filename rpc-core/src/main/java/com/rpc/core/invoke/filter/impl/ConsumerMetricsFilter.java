package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.filter.FilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.RpcFilter;
import com.rpc.core.observability.metrics.ServiceMetrics;
import com.rpc.core.observability.metrics.ServiceMetricsManager;
import lombok.extern.slf4j.Slf4j;

/**
 * consumer 侧指标过滤器。
 *
 * 负责统计一次 consumer 调用的耗时、成功和失败情况，
 * 让框架可以按服务维度观察 consumer 视角下的调用体验。
 */
@Slf4j
public class ConsumerMetricsFilter implements RpcFilter {
    @Override
    public FilterPhase phase() {
        return FilterPhase.CONSUMER;
    }

    @Override
    public int order() {
        return 10;
    }

    /**
     * 记录本次调用的耗时与成功状态。
     */
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
            log.debug("RPC consumer invoke cost={}us, service={}.{}",
                    (System.nanoTime() - start) / 1000,
                    context.getRequest().getServiceName(),
                    context.getRequest().getMethodName());
        }
    }

    /**
     * 按服务维度写入指标。
     * consumer 侧更关心“调用某个服务是否成功、耗时多少”，而不是 provider 具体是哪台机器。
     */
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
}
