package com.rpc.core.extension.loadbalance.factory;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.spi.ExtensionFactory;

/**
 * 负载均衡器工厂。
 *
 * 上层代码不应该直接依赖具体负载均衡实现类，
 * 而是通过工厂按配置名称获取对应策略对象。
 */
public class LoadBalancerFactory {
    /** 获取默认负载均衡器。 */
    public static LoadBalancer getDefaultLoadBalancer() {
        return ExtensionFactory.getDefaultExtension(LoadBalancer.class);
    }

    /**
     * 按名称获取负载均衡器。
     *
     * 如果没有显式指定名称，则回退到默认负载均衡策略。
     */
    public static LoadBalancer getLoadBalancer(String name) {
        if (name == null || name.isEmpty()) {
            return getDefaultLoadBalancer();
        }
        return ExtensionFactory.getExtension(LoadBalancer.class, name);
    }
}
