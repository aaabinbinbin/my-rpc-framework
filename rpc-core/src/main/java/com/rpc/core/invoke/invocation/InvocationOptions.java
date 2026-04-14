package com.rpc.core.invoke.invocation;

import lombok.Builder;
import lombok.Value;

/**
 * 一次调用最终生效的运行时选项。
 *
 * 这是方法级配置在进入执行链之前的标准化结果，
 * 后续的限流、cluster、transport 等层都读取这里的结果，而不是直接读 MethodConfig。
 */
@Value
@Builder
public class InvocationOptions {
    /** 本次调用允许的重试次数。 */
    int retryTimes;
    /** 本次调用使用的集群容错策略。 */
    ClusterStrategy clusterStrategy;
    /** 本次调用的读取超时。 */
    Integer readTimeout;
    /** 本次调用使用的序列化器名称。 */
    String serializerName;
    /** 本次调用使用的负载均衡器名称。 */
    String loadBalancerName;
    /** 本次调用是否开启限流。 */
    boolean rateLimitEnabled;
    /** 本次调用限流配额。 */
    int rateLimitPermitsPerSecond;
    /** 熔断器作用范围，决定是按服务还是按方法维度统计。 */
    CircuitBreakerScope circuitBreakerScope;
}
