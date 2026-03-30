package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.FilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.RpcFilter;
import org.slf4j.MDC;

public class ProviderMdcFilter implements RpcFilter {
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
        RpcContext rpcContext = context.getRpcContext();
        try {
            // 服务端从重建后的 RpcContext 恢复 MDC，
            // 这样服务执行期间的日志就能和原始消费端请求对应起来。
            put("rpcRequestId", rpcContext.getRequestId());
            put("rpcTraceId", rpcContext.getTraceId());
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
