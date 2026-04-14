package com.rpc.core.invoke.filter.api;

import com.rpc.core.extension.spi.SPI;
import com.rpc.core.invoke.filter.context.FilterContext;

/**
 * RPC 过滤器扩展点。
 *
 * 所处阶段：调用链按 FilterPhase 选择过滤器并组成责任链时。
 * 主要职责：允许通过 SPI 插入 trace、MDC、指标、限流、熔断、降级等横切能力。
 *
 * 注意事项：过滤器实现必须保持线程安全，因为同一个 SPI 实例会被多次请求复用。
 */
@SPI
public interface RpcFilter {
    /**
     * 返回过滤器所属阶段。
     */
    FilterPhase phase();

    /**
     * 返回默认执行顺序。
     *
     * 注意事项：该顺序可被 rpc.filter.order.{name} 覆盖。
     */
    int order();

    /**
     * 执行过滤器逻辑。
     *
     * 边界处理：可以调用 chain.proceed 继续链路，也可以直接返回响应或抛出异常实现短路。
     */
    Object invoke(FilterContext context, FilterChain chain) throws Exception;
}

