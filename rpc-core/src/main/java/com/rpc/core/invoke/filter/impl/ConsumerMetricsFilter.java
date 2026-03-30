package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.filter.FilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.RpcFilter;
import com.rpc.core.observability.metrics.ServiceMetrics;
import com.rpc.core.observability.metrics.ServiceMetricsManager;
import lombok.extern.slf4j.Slf4j;

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

    private void record(FilterContext context, long latencyNanos, boolean failed) {
        // 指标按逻辑服务名聚合，而不是按提供者地址聚合，
        // 因为这里衡量的是消费端对服务契约的调用体验。
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

