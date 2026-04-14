package com.rpc.core.extension.loadbalance.factory;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.loadbalance.impl.RandomLoadBalancer;
import com.rpc.core.extension.loadbalance.impl.RoundRobinLoadBalancer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("测试类：负载均衡工厂边界测试")
class LoadBalancerFactoryTest {
    @DisplayName("验证空名称回退到默认负载均衡器")
    @Test
    void shouldReturnDefaultLoadBalancerWhenNameMissing() {
        LoadBalancer byNull = LoadBalancerFactory.getLoadBalancer(null);
        LoadBalancer byEmpty = LoadBalancerFactory.getLoadBalancer("");

        assertNotNull(byNull);
        assertNotNull(byEmpty);
        assertInstanceOf(RandomLoadBalancer.class, byNull);
        assertInstanceOf(RandomLoadBalancer.class, byEmpty);
    }

    @DisplayName("验证已注册名称会解析到对应负载均衡器")
    @Test
    void shouldResolveLoadBalancerByName() {
        assertInstanceOf(RandomLoadBalancer.class, LoadBalancerFactory.getLoadBalancer("random"));
        assertInstanceOf(RoundRobinLoadBalancer.class, LoadBalancerFactory.getLoadBalancer("roundrobin"));
    }

    @DisplayName("验证未知负载均衡名称会快速失败")
    @Test
    void shouldFailFastWhenLoadBalancerNameUnknown() {
        assertThrows(RuntimeException.class, () -> LoadBalancerFactory.getLoadBalancer("missing-load-balancer"));
    }
}
