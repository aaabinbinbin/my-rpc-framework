package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.FilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.RpcFilter;
import org.slf4j.MDC;

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
        // 把调用维度写入 MDC 后，当前请求期间产出的日志就会自动带上
        // request/trace/service/method 信息，而不用修改每一条日志语句。
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

