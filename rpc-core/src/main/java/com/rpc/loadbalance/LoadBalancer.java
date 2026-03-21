package com.rpc.loadbalance;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 负载均衡器接口
 */
public interface LoadBalancer {
    /**
     * 从服务列表中选择一个节点
     * @param serviceName 服务名称
     * @param addresses 服务地址列表
     * @return 选中的地址
     */
    InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses);

    /**
     * 获取负载均衡策略名称
     */
    String getName();
}
