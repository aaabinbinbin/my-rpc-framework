package com.rpc.core.invoke.filter.api;

import com.rpc.core.invoke.filter.context.FilterContext;

/**
 * 过滤器责任链接口。
 *
 * 所处阶段：consumer、invoker、provider 任一过滤器执行过程中。
 * 主要职责：让当前过滤器把控制权交给下一个过滤器或最终业务调用。
 */
@FunctionalInterface
public interface FilterChain {
    /**
     * 继续执行链路。
     *
     * 注意事项：过滤器可以选择不调用 proceed，从而实现限流拒绝、熔断降级、短路返回等行为。
     */
    Object proceed(FilterContext context) throws Exception;
}

