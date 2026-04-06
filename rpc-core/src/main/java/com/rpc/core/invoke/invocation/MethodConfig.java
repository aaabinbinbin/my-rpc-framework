package com.rpc.core.invoke.invocation;

import lombok.Builder;
import lombok.Value;

/**
 * 方法级配置。
 *
 * 用于描述“某个服务的某个方法”在调用时应覆盖哪些默认选项，
 * 例如重试次数、超时、负载均衡器、序列化器、限流参数等。
 */
@Value
@Builder
public class MethodConfig {
    /** 目标服务名，通常是接口全限定名。 */
    String serviceName;
    /** 目标方法名。 */
    String methodName;
    /** 方法级重试次数。 */
    Integer retryTimes;
    /** 方法级集群策略。 */
    ClusterStrategy clusterStrategy;
    /** 方法级读取超时。 */
    Integer readTimeout;
    /** 方法级序列化器名称。 */
    String serializerName;
    /** 方法级负载均衡器名称。 */
    String loadBalancerName;
    /** 方法级限流开关。 */
    Boolean rateLimitEnabled;
    /** 方法级限流配额。 */
    Integer rateLimitPermitsPerSecond;
    /** 方法级熔断作用域。 */
    CircuitBreakerScope circuitBreakerScope;
}
