package com.rpc.core.invoke.filter.runtime;

import com.rpc.core.invoke.filter.api.FilterChain;
import com.rpc.core.invoke.filter.api.FilterInvoker;
import com.rpc.core.invoke.filter.api.RpcFilter;
import com.rpc.core.invoke.filter.context.FilterContext;

import java.util.List;

/**
 * 默认过滤器链实现。
 *
 * 这个类把多个 RpcFilter 串成一条责任链：
 * 每个 filter 执行完后，把控制权交给下一个 filter；
 * 所有 filter 执行完后，才会真正进入终点调用 terminalInvoker。
 */
public class DefaultFilterChain implements FilterChain {
    /** 当前阶段已经解析好的过滤器列表。 */
    private final List<RpcFilter> filters;
    /** 当过滤器全部执行完后真正要调用的终点逻辑。 */
    private final FilterInvoker terminalInvoker;
    /** 当前链执行到第几个过滤器。 */
    private final int index;

    public DefaultFilterChain(List<RpcFilter> filters, FilterInvoker terminalInvoker) {
        this(filters, terminalInvoker, 0);
    }

    private DefaultFilterChain(List<RpcFilter> filters, FilterInvoker terminalInvoker, int index) {
        this.filters = filters;
        this.terminalInvoker = terminalInvoker;
        this.index = index;
    }

    /**
     * 执行链上的下一个节点。
     *
     * 如果当前已经没有更多 filter，
     * 就直接执行 terminalInvoker；
     * 否则让当前 filter 接管，并把“剩余链路”继续传下去。
     */
    @Override
    public Object proceed(FilterContext context) throws Exception {
        if (index >= filters.size()) {
            return terminalInvoker.invoke(context);
        }
        RpcFilter next = filters.get(index);
        return next.invoke(context, new DefaultFilterChain(filters, terminalInvoker, index + 1));
    }
}
