package com.rpc.config;

import com.rpc.loadbalance.LoadBalancer;
import com.rpc.loadbalance.impl.RandomLoadBalancer;
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

    // 负载均衡器
    @Builder.Default
    private LoadBalancer loadBalancer = new RandomLoadBalancer();

    // 重试次数
    @Builder.Default
    private int retryTimes = 3;

    public static RpcClientConfig custom() {
        return RpcClientConfig.builder().build();
    }
}
