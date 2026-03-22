package com.rpc.loadbalance.factory;

import com.rpc.loadbalance.LoadBalancer;
import com.rpc.serialize.Serializer;
import com.rpc.spi.ExtensionFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 负载均衡器工厂
 */
@Slf4j
public class LoadBalancerFactory {
    /** 存储所有可用的序列化器 */
    private static final Map<String, LoadBalancer> LOAD_BALANCE_MAP = new HashMap<>();
    private static LoadBalancer DEFAULT_LOAD_BALANCER;

    static {
        List<LoadBalancer> loadBalancerList = ExtensionFactory.getExtensions(LoadBalancer.class);
        for (LoadBalancer loadBalancer : loadBalancerList) {
            LOAD_BALANCE_MAP.put(loadBalancer.getName(), loadBalancer);
            log.info("加载负载均衡器: {} -> {}",
                    loadBalancer.getName(),
                    loadBalancer.getClass().getSimpleName());
        }
        DEFAULT_LOAD_BALANCER = ExtensionFactory.getDefaultExtension(LoadBalancer.class);
        log.info("默认负载均衡器: {}", DEFAULT_LOAD_BALANCER.getClass().getSimpleName());
    }

    /**
     * 获取默认负载均衡器
     */
    public static LoadBalancer getDefaultLoadBalancer() {
        return DEFAULT_LOAD_BALANCER;
    }

    /**
     * 根据名称获取负载均衡器
     */
    public static LoadBalancer getLoadBalancer(String name) {
        if (name == null || name.isEmpty()) {
            return DEFAULT_LOAD_BALANCER;
        }
        return ExtensionFactory.getExtension(LoadBalancer.class, name);
    }
}
