package com.rpc.core.config;

import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.MethodConfig;
import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.loadbalance.factory.LoadBalancerFactory;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.factory.SerializerFactory;
import com.rpc.core.transport.TransportType;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class RpcClientConfig {
    @Builder.Default
    private TransportType transportType = TransportType.NETTY;

    @Builder.Default
    private int connectTimeout = 5000;

    @Builder.Default
    private int readTimeout = 10000;

    @Builder.Default
    private int heartbeatInterval = 30000;

    @Builder.Default
    private int writerIdleTime = 30000;

    @Builder.Default
    private int readerIdleTime = 10000;

    @Builder.Default
    private LoadBalancer loadBalancer = LoadBalancerFactory.getDefaultLoadBalancer();

    @Builder.Default
    private String serializerName = "protobuf";

    @Builder.Default
    private int retryTimes = 3;

    @Builder.Default
    private ClusterStrategy clusterStrategy = ClusterStrategy.FAIL_OVER;

    @Builder.Default
    private List<MethodConfig> methodConfigs = new ArrayList<>();

    @Builder.Default
    private boolean reconnectEnabled = true;

    @Builder.Default
    private int reconnectMaxRetryTimes = 5;

    @Builder.Default
    private int reconnectInitialDelaySeconds = 2;

    @Builder.Default
    private int reconnectMaxDelaySeconds = 60;

    @Builder.Default
    private boolean reconnectJitterEnabled = true;

    @Builder.Default
    private int reconnectJitterMinSeconds = 0;

    @Builder.Default
    private int reconnectJitterMaxSeconds = 1;

    @Builder.Default
    private boolean discoveryPreheatEnabled = false;

    @Builder.Default
    private List<String> discoveryPreheatServices = new ArrayList<>();

    @Builder.Default
    private long discoveryCacheTtlMillis = 30000L;

    @Builder.Default
    private boolean discoveryAllowStaleOnFailure = true;

    @Builder.Default
    private DegradationPolicy degradationPolicy = null;

    @Builder.Default
    private boolean enableDegradation = false;

    @Builder.Default
    private int degradationFailureThreshold = 10;

    @Builder.Default
    private boolean rateLimitEnabled = false;

    @Builder.Default
    private int rateLimitPermitsPerSecond = 100;

    @Builder.Default
    private float circuitBreakerFailureRateThreshold = 50.0f;

    @Builder.Default
    private int circuitBreakerMinNumberOfCalls = 10;

    @Builder.Default
    private long circuitBreakerWaitDurationInOpenStateMillis = 30000L;

    @Builder.Default
    private int circuitBreakerPermittedHalfOpenCalls = 5;

    public static RpcClientConfig custom() {
        return RpcClientConfig.builder().build();
    }

    public Serializer resolveSerializer() {
        return SerializerFactory.getSerializer(serializerName);
    }
}

