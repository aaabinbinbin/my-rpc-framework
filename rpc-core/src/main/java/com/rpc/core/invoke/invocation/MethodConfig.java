package com.rpc.core.invoke.invocation;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MethodConfig {
    String serviceName;
    String methodName;
    Integer retryTimes;
    ClusterStrategy clusterStrategy;
    Integer readTimeout;
    String serializerName;
    String loadBalancerName;
    Boolean rateLimitEnabled;
    Integer rateLimitPermitsPerSecond;
    CircuitBreakerScope circuitBreakerScope;
}

