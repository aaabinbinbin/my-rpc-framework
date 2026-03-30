package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.FilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.RpcFilter;
import com.rpc.core.protocol.RpcRequest;

public class TraceFilter implements RpcFilter {
    public static final String TRACE_ID = "traceId";

    @Override
    public FilterPhase phase() {
        return FilterPhase.CONSUMER;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        RpcContext rpcContext = context.getRpcContext();
        RpcRequest request = context.getRequest();
        // traceId 通过请求附件透传，
        // 这样下游提供者、日志过滤器和后续中间件都能共享同一条调用链标识。
        request.getAttachments().put(TRACE_ID, rpcContext.ensureTraceId());
        return chain.proceed(context);
    }
}

