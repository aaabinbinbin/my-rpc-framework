package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.api.FilterChain;
import com.rpc.core.invoke.filter.context.FilterContext;
import com.rpc.core.invoke.filter.api.FilterPhase;
import com.rpc.core.invoke.filter.api.RpcFilter;
import org.slf4j.MDC;

/**
 * provider 侧 MDC 过滤器。
 * provider 在收到请求后会先恢复 RpcContext，
 * 这个过滤器再把上下文中的 requestId、traceId、service、method 写入 MDC，
 * 方便服务端日志和 consumer 侧请求链路对齐。
 */
public class ProviderMdcFilter implements RpcFilter {
    @Override
    public FilterPhase phase() {
        return FilterPhase.PROVIDER;
    }

    @Override
    public int order() {
        return 0;
    }

    /** 在 provider 业务执行期间写入 MDC，执行后及时清理。 */
    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        RpcContext rpcContext = context.getRpcContext();
        try {
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
