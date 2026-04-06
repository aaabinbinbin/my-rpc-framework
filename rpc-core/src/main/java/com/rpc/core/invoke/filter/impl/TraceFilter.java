package com.rpc.core.invoke.filter.impl;

import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.FilterChain;
import com.rpc.core.invoke.filter.FilterContext;
import com.rpc.core.invoke.filter.FilterPhase;
import com.rpc.core.invoke.filter.RpcFilter;
import com.rpc.core.protocol.RpcRequest;

/**
 * trace 透传过滤器。
 *
 * 这个过滤器运行在 consumer 阶段，
 * 负责确保本次调用拥有 traceId，并把它写入请求附件，
 * 这样 provider 和后续日志 / 监控链路就能沿用同一个调用标识。
 */
public class TraceFilter implements RpcFilter {
    /** 请求附件里保存 traceId 的 key。 */
    public static final String TRACE_ID = "traceId";

    @Override
    public FilterPhase phase() {
        return FilterPhase.CONSUMER;
    }

    @Override
    public int order() {
        return 0;
    }

    /**
     * 确保当前调用具备 traceId，并把它透传到 RpcRequest 附件中。
     */
    @Override
    public Object invoke(FilterContext context, FilterChain chain) throws Exception {
        RpcContext rpcContext = context.getRpcContext();
        RpcRequest request = context.getRequest();
        request.getAttachments().put(TRACE_ID, rpcContext.ensureTraceId());
        return chain.proceed(context);
    }
}
