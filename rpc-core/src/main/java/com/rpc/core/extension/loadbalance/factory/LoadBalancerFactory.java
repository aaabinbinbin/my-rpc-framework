package com.rpc.core.extension.loadbalance.factory;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.spi.ExtensionFactory;

/**
 * 负载均衡扩展工厂。
 */
public class LoadBalancerFactory {
    public static LoadBalancer getDefaultLoadBalancer() {
        return ExtensionFactory.getDefaultExtension(LoadBalancer.class);
    }

    public static LoadBalancer getLoadBalancer(String name) {
        if (name == null || name.isEmpty()) {
            return getDefaultLoadBalancer();
        }
    // 负载均衡器按名称懒加载，避免客户端启动时把所有实现都实例化一遍。
        return ExtensionFactory.getExtension(LoadBalancer.class, name);
    }
}
