package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.FilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.RpcFilter;
import org.slf4j.MDC;

/**
 * consumer 侧 MDC 过滤器。
 *
 * 它的作用不是修改请求本身，
 * 而是把 requestId、traceId、service、method 等信息写入日志上下文 MDC，
 * 让当前请求生命周期内产生的日志自动带上这些字段。
 */
public class MdcFilter implements RpcFilter {
    @Override
    public FilterPhase phase() {
        return FilterPhase.CONSUMER;
    }

    @Override
    public int order() {
        return 5;
    }

    /**
     * 在执行后续链路前写入 MDC，结束后清理，防止线程复用导致日志串请求。
     */
    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        RpcContext rpcContext = context.getRpcContext();
        try {
            put("rpcRequestId", rpcContext.getRequestId());
            put("rpcTraceId", rpcContext.ensureTraceId());
            put("rpcService", context.getRequest().getServiceName());
            put("rpcMethod", context.getRequest().getMethodName());
            return chain.proceed(context);
        } finally {
            clear();
        }
    }

    private void put(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    private void clear() {
        MDC.remove("rpcRequestId");
        MDC.remove("rpcTraceId");
        MDC.remove("rpcService");
        MDC.remove("rpcMethod");
    }
}
