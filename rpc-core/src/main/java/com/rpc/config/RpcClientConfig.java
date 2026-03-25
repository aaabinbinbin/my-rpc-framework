package com.rpc.config;

import com.rpc.faulttolerance.DegradationPolicy;
import com.rpc.loadbalance.LoadBalancer;
import com.rpc.loadbalance.factory.LoadBalancerFactory;
import lombok.Builder;
import lombok.Data;

/**
 * RPC 客户端配置
 */
@Data
@Builder
public class RpcClientConfig {
    // 连接超时时间（毫秒）
    private int connectTimeout = 5000;

    // 读取超时时间（毫秒）
    private int readTimeout = 10000;

    // 心跳间隔时间（毫秒），默认30秒
    @Builder.Default
    private int heartbeatInterval = 30000;

    // 写空闲超时时间（毫秒），用于触发心跳
    @Builder.Default
    private int writerIdleTime = 30000;

    // 读空闲超时时间（毫秒），用于检测连接是否存活
    @Builder.Default
    private int readerIdleTime = 10000;

    // 负载均衡器
    @Builder.Default
    private LoadBalancer loadBalancer = LoadBalancerFactory.getDefaultLoadBalancer();

    // 重试次数
    @Builder.Default
    private int retryTimes = 3;

    // ========== 可选模块配置 ==========
    
    // 降级策略（可选，不配置则不启用降级）
    @Builder.Default
    private DegradationPolicy degradationPolicy = null;
    
    // 是否启用降级（默认 false）
    @Builder.Default
    private boolean enableDegradation = false;
    
    // 降级的触发条件：连续失败次数达到此阈值时触发降级（默认 10 次）
    @Builder.Default
    private int degradationFailureThreshold = 10;

    public static RpcClientConfig custom() {
        return RpcClientConfig.builder().build();
    }
}
