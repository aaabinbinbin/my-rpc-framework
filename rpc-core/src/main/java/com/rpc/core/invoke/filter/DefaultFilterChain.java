package com.rpc.core.invoke.filter;

import java.util.List;

public class DefaultFilterChain implements FilterChain {
    private final List<RpcFilter> filters;
    private final FilterInvoker terminalInvoker;
    // index 指向当前应该执行到第几个 filter。
    private final int index;

    public DefaultFilterChain(List<RpcFilter> filters, FilterInvoker terminalInvoker) {
        this(filters, terminalInvoker, 0);
    }

    private DefaultFilterChain(List<RpcFilter> filters, FilterInvoker terminalInvoker, int index) {
        this.filters = filters;
        this.terminalInvoker = terminalInvoker;
        this.index = index;
    }

    @Override
    public Object proceed(FilterContext context) throws Exception {
        if (index >= filters.size()) {
            // 所有 filter 都执行完后，才真正进入末端调用。
            return terminalInvoker.invoke(context);
        }
        RpcFilter next = filters.get(index);
        // 每执行一个 filter，就把 index + 1 传给下一段链。
        return next.invoke(context, new DefaultFilterChain(filters, terminalInvoker, index + 1));
    }
}

