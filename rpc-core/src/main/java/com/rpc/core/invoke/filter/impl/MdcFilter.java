package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.api.FilterChain;
import com.rpc.core.invoke.filter.context.FilterContext;
import com.rpc.core.invoke.filter.api.FilterPhase;
import com.rpc.core.invoke.filter.api.RpcFilter;
import org.slf4j.MDC;

/**
 * consumer 侧 MDC 过滤器。
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

    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        RpcContext rpcContext = context.getRpcContext();
        try {
            put("rpcRequestId", resolveRequestId(context, rpcContext));
            put("rpcTraceId", rpcContext != null ? rpcContext.ensureTraceId() : null);
            put("rpcService", context.getRequest().getServiceName());
            put("rpcMethod", context.getRequest().getMethodName());
            return chain.proceed(context);
        } finally {
            clear();
        }
    }

    private String resolveRequestId(FilterContext context, RpcContext rpcContext) {
        String requestId = context.getRequest().getRequestId();
        if (requestId == null && rpcContext != null) {
            requestId = rpcContext.getRequestId();
        }
        return requestId;
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
