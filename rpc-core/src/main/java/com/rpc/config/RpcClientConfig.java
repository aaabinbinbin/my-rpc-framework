package com.rpc.config;

import com.rpc.faulttolerance.DegradationPolicy;
import com.rpc.loadbalance.LoadBalancer;
import com.rpc.loadbalance.factory.LoadBalancerFactory;
import com.rpc.transport.TransportType;
import lombok.Builder;
import lombok.Data;

/**
 * RPC client config.
 */
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
    private int retryTimes = 3;

    @Builder.Default
    private DegradationPolicy degradationPolicy = null;

    @Builder.Default
    private boolean enableDegradation = false;

    @Builder.Default
    private int degradationFailureThreshold = 10;

    public static RpcClientConfig custom() {
        return RpcClientConfig.builder().build();
    }
}
