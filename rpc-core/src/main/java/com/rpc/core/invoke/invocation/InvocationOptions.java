package com.rpc.core.invoke.invocation;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class InvocationOptions {
    int retryTimes;
    ClusterStrategy clusterStrategy;
    Integer readTimeout;
    String serializerName;
    String loadBalancerName;
    boolean rateLimitEnabled;
    int rateLimitPermitsPerSecond;
    CircuitBreakerScope circuitBreakerScope;
}

