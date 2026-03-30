package com.rpc.core.invoke.invocation;

import com.rpc.core.protocol.RpcRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvocationOptionsResolverTest {
    @Test
    void shouldUseMethodLevelOverrides() {
        DefaultInvocationOptionsResolver resolver = new DefaultInvocationOptionsResolver(
                InvocationOptions.builder()
                        .retryTimes(3)
                        .clusterStrategy(ClusterStrategy.FAIL_OVER)
                        .readTimeout(3000)
                        .serializerName("protobuf")
                        .loadBalancerName("random")
                        .rateLimitEnabled(false)
                        .rateLimitPermitsPerSecond(100)
                        .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                        .build(),
                List.of(MethodConfig.builder()
                        .serviceName("svc")
                        .methodName("fast")
                        .retryTimes(0)
                        .clusterStrategy(ClusterStrategy.FAIL_FAST)
                        .readTimeout(500)
                        .serializerName("json")
                        .loadBalancerName("roundRobin")
                        .rateLimitEnabled(true)
                        .rateLimitPermitsPerSecond(10)
                        .circuitBreakerScope(CircuitBreakerScope.METHOD)
                        .build())
        );

        InvocationOptions options = resolver.resolve(RpcRequest.builder()
                .serviceName("svc")
                .methodName("fast")
                .build());

        assertEquals(0, options.getRetryTimes());
        assertEquals(ClusterStrategy.FAIL_FAST, options.getClusterStrategy());
        assertEquals(500, options.getReadTimeout());
        assertEquals("json", options.getSerializerName());
        assertEquals("roundRobin", options.getLoadBalancerName());
        assertEquals(true, options.isRateLimitEnabled());
        assertEquals(10, options.getRateLimitPermitsPerSecond());
        assertEquals(CircuitBreakerScope.METHOD, options.getCircuitBreakerScope());
    }

    @Test
    void shouldUseGlobalDefaultsWhenNoMethodConfig() {
        DefaultInvocationOptionsResolver resolver = new DefaultInvocationOptionsResolver(
                InvocationOptions.builder()
                        .retryTimes(2)
                        .clusterStrategy(ClusterStrategy.FAIL_OVER)
                        .readTimeout(3000)
                        .serializerName("protobuf")
                        .loadBalancerName("random")
                        .rateLimitEnabled(false)
                        .rateLimitPermitsPerSecond(100)
                        .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                        .build(),
                List.of()
        );

        InvocationOptions options = resolver.resolve(RpcRequest.builder()
                .serviceName("svc")
                .methodName("normal")
                .build());

        assertEquals(2, options.getRetryTimes());
        assertEquals(ClusterStrategy.FAIL_OVER, options.getClusterStrategy());
        assertEquals(3000, options.getReadTimeout());
        assertEquals("protobuf", options.getSerializerName());
        assertEquals("random", options.getLoadBalancerName());
        assertEquals(false, options.isRateLimitEnabled());
        assertEquals(100, options.getRateLimitPermitsPerSecond());
        assertEquals(CircuitBreakerScope.SERVICE, options.getCircuitBreakerScope());
    }
}

