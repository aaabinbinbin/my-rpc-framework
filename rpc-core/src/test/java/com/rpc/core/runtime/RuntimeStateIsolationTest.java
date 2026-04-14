package com.rpc.core.runtime;

import com.rpc.core.api.bootstrap.RpcConsumerBootstrap;
import com.rpc.core.api.bootstrap.RpcProviderBootstrap;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfig;
import com.rpc.core.resilience.CircuitBreakerState;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.RpcTransport;
import com.rpc.core.transport.netty.server.statistics.StatisticsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：运行时状态隔离测试")
class RuntimeStateIsolationTest {
    @AfterEach
    void tearDown() {
        FilterRuntimeConfig.resetAll();
        CircuitBreakerManager.getInstance().resetAll();
        StatisticsManager.getInstance().shutdown();
    }

    @DisplayName("验证重置消费端运行时状态在最后启动Closes场景")
    @Test
    void shouldResetConsumerRuntimeStateAfterLastBootstrapCloses() throws Exception {
        RpcConsumerBootstrap first = instantiateConsumerBootstrap();
        RpcConsumerBootstrap second = instantiateConsumerBootstrap();
        CircuitBreakerManager manager = CircuitBreakerManager.getInstance();
        FilterRuntimeConfig.configureConsumerDegradation(
                true,
                DegradationPolicyFactory.create("defaultValue", Map.of("svc#echo", "fallback"))
        );
        manager.configure(10.0f, 1, 1_000L, 1);
        manager.getServiceCircuitBreaker("svc").recordFailure();

        first.close();

        assertTrue(FilterRuntimeConfig.isConsumerDegradationEnabled());
        assertNotNull(FilterRuntimeConfig.getConsumerDegradationPolicy());
        assertEquals(CircuitBreakerState.OPEN, manager.getServiceCircuitBreaker("svc").getState());

        second.close();

        assertFalse(FilterRuntimeConfig.isConsumerDegradationEnabled());
        assertNull(FilterRuntimeConfig.getConsumerDegradationPolicy());
        assertEquals(CircuitBreakerState.CLOSED, manager.getServiceCircuitBreaker("svc").getState());
    }

    @DisplayName("验证重置服务端运行时状态在最后启动Closes场景")
    @Test
    void shouldResetProviderRuntimeStateAfterLastBootstrapCloses() throws Exception {
        RpcProviderBootstrap first = instantiateProviderBootstrap();
        RpcProviderBootstrap second = instantiateProviderBootstrap();
        FilterRuntimeConfig.configureProviderRateLimit(true, 1);
        FilterRuntimeConfig.configureProviderDegradation(
                true,
                DegradationPolicyFactory.create("defaultValue", Map.of("svc#echo", "fallback"))
        );

        first.close();

        assertTrue(FilterRuntimeConfig.isProviderDegradationEnabled());
        assertNotNull(FilterRuntimeConfig.getProviderDegradationPolicy());

        second.close();

        assertFalse(FilterRuntimeConfig.isProviderDegradationEnabled());
        assertNull(FilterRuntimeConfig.getProviderDegradationPolicy());
        assertTrue(FilterRuntimeConfig.tryAcquireProvider("svc#echo"));
    }

    @DisplayName("验证允许统计管理器To重启在关闭场景")
    @Test
    void shouldAllowStatisticsManagerToRestartAfterShutdown() {
        StatisticsManager manager = StatisticsManager.getInstance();
        manager.register("svc");
        manager.startPeriodicReport(1, 1, java.util.concurrent.TimeUnit.HOURS);
        manager.shutdown();

        manager.register("svc2");
        manager.startPeriodicReport(1, 1, java.util.concurrent.TimeUnit.HOURS);
        assertNotNull(manager.getStatistics("svc2"));
    }

    private RpcConsumerBootstrap instantiateConsumerBootstrap() throws Exception {
        Constructor<RpcConsumerBootstrap> constructor =
                RpcConsumerBootstrap.class.getDeclaredConstructor(com.rpc.core.discovery.ServiceDiscovery.class, RpcTransport.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, new NoopTransport());
    }

    private RpcProviderBootstrap instantiateProviderBootstrap() throws Exception {
        Constructor<RpcProviderBootstrap> constructor =
                RpcProviderBootstrap.class.getDeclaredConstructor(com.rpc.core.registry.ServiceRegistry.class, RpcServer.class, RpcFrameworkConfig.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, new NoopServer(), new RpcFrameworkConfig());
    }

    static class NoopTransport implements RpcTransport {
        @Override
        public com.rpc.core.protocol.message.RpcResponse sendRequest(com.rpc.core.protocol.message.RpcRequest rpcRequest) {
            return com.rpc.core.protocol.message.RpcResponse.success("ok", rpcRequest.getRequestId());
        }

        @Override
        public void close() {
        }
    }

    static class NoopServer implements RpcServer {
        @Override
        public void start() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public com.rpc.core.registry.LocalRegistry getLocalRegistry() {
            return null;
        }
    }
}
