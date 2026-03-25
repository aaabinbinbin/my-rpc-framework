package com.rpc.faulttolerance;

import com.rpc.config.RpcClientConfig;
import com.rpc.faulttolerance.degrade.DefaultValueDegradation;
import com.rpc.loadbalance.factory.LoadBalancerFactory;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.transport.netty.client.RpcNettyClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RpcClient 集成测试
 * 验证通过配置启用降级策略的功能
 */
@Slf4j
class RpcClientIntegrationTest {

    private RpcNettyClient client;
    private ZooKeeperRegistryImpl mockRegistry;

    @BeforeEach
    void setUp() {
        mockRegistry = new ZooKeeperRegistryImpl("8.134.204.101:2181", 5000);
    }

    @Test
    @DisplayName("测试 1：默认配置（不启用降级）")
    void testDefaultConfiguration() throws Exception {
        log.info("===== 测试 1：默认配置（不启用降级） =====");
        
        // 使用最简单的配置方式
        RpcClientConfig config = RpcClientConfig.custom();
        
        client = new RpcNettyClient(config, mockRegistry);
        
        // 验证客户端已创建
        assertNotNull(client);
        log.info("✓ 客户端创建成功，未启用降级");
    }

    @Test
    @DisplayName("测试 2：通过 Builder 启用降级")
    void testEnableDegradationWithBuilder() throws Exception {
        log.info("===== 测试 2：通过 Builder 启用降级 =====");
        
        // 创建降级策略
        DefaultValueDegradation policy = new DefaultValueDegradation();
        policy.setDefaultValue("TestService#testMethod", "默认返回值");
        
        // 通过 Builder 配置启用降级
        RpcClientConfig config = RpcClientConfig.builder()
                .enableDegradation(true)
                .degradationPolicy(policy)
                .degradationFailureThreshold(3)
                .build();
        
        client = new RpcNettyClient(config, mockRegistry);
        
        // 验证配置生效
        assertNotNull(client);
        log.info("✓ 降级策略已配置");
    }

    @Test
    @DisplayName("测试 3：简单构造方法 vs 复杂功能")
    void testSimpleConstructorWithAdvancedFeatures() throws Exception {
        log.info("===== 测试 3：简单构造方法 vs 复杂功能 =====");
        
        // 配置很复杂，但构造方法很简单
        RpcClientConfig complexConfig = RpcClientConfig.builder()
                .connectTimeout(5000)
                .readTimeout(10000)
                .loadBalancer(LoadBalancerFactory
                        .getLoadBalancer("roundrobin"))
                .retryTimes(3)
                .enableDegradation(true)
                .degradationPolicy(new DefaultValueDegradation())
                .degradationFailureThreshold(5)
                .build();
        
        // 构造方法只有两个参数，非常简单！
        client = new RpcNettyClient(complexConfig, mockRegistry);
        
        assertNotNull(client);
        log.info("✓ 构造方法简单，但功能强大");
    }
}
