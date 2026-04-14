package com.rpc.core.invoke.filter.api;

/**
 * 过滤器执行阶段。
 *
 * 设计原因：同一套 Filter SPI 需要支持 consumer 总入口、单次实例调用和 provider 服务端调用三类链路，
 * 用 phase 可以避免某个过滤器被挂到错误阶段。
 */
public enum FilterPhase {
    /** consumer 侧服务级链路，适合 trace、MDC、服务级指标等。 */
    CONSUMER,
    /** consumer 侧单次实例调用链路，适合实例级熔断、真实 requestId MDC 等。 */
    INVOKER,
    /** provider 侧业务执行前后链路，适合服务端限流、MDC、指标和降级。 */
    PROVIDER
}

