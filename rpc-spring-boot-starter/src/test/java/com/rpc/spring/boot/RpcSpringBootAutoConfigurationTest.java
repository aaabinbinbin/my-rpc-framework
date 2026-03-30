package com.rpc.spring.boot;

import com.rpc.core.api.annotation.RpcReference;
import com.rpc.core.api.bootstrap.RpcConsumerBootstrap;
import com.rpc.core.api.bootstrap.RpcProviderBootstrap;
import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.invoke.invocation.CircuitBreakerScope;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.MethodConfig;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.spring.boot.support.DemoService;
import com.rpc.spring.boot.support.DemoServiceImpl;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.RpcTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RpcSpringBootAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestSupportConfiguration.class, ConsumerBeanConfiguration.class)
            .withPropertyValues(
                    "rpc.spring.enabled=true",
                    "rpc.spring.scan-packages=com.rpc.spring.boot.support",
                    "rpc.transport=socket",
                    "rpc.server.host=192.168.1.10",
                    "rpc.server.port=9090",
                    "rpc.server.rate-limit.enabled=true",
                    "rpc.server.degradation.policy=defaultValue",
                    "rpc.client.rate-limit.enabled=true",
                    "rpc.client.degradation.policy=defaultValue",
                    "rpc.filter.provider=providerRateLimit,providerMdc",
                    "rpc.client.methods[0].service-name=com.rpc.spring.boot.support.DemoService",
                    "rpc.client.methods[0].method-name=hello",
                    "rpc.client.methods[0].retry-times=0",
                    "rpc.client.methods[0].cluster-strategy=failfast",
                    "rpc.client.methods[0].read-timeout=1234",
                    "rpc.client.methods[0].serializer-name=json",
                    "rpc.client.methods[0].load-balancer-name=roundRobin",
                    "rpc.client.methods[0].rate-limit-enabled=true",
                    "rpc.client.methods[0].rate-limit-permits-per-second=9",
                    "rpc.client.methods[0].circuit-breaker-scope=method"
            )
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(RpcSpringBootAutoConfiguration.class));

    @Test
    void shouldAutoConfigureRpcSpringManagerAndScanRpcServices() {
        contextRunner.run(context -> {
            ConsumerBean consumerBean = context.getBean(ConsumerBean.class);
            CapturingRpcServer rpcServer = context.getBean(CapturingRpcServer.class);
            RpcFrameworkConfig frameworkConfig = context.getBean(RpcFrameworkConfig.class);

            assertNotNull(context.getBean(RpcSpringBootAutoConfiguration.class));
            assertNotNull(context.getBean(DemoServiceImpl.class));
            assertNotNull(consumerBean.demoService);
            assertEquals(DemoService.class.getName(), rpcServer.localRegistry.registeredServiceName);
            assertEquals("192.168.1.10", frameworkConfig.getServerHost());
            assertEquals(9090, frameworkConfig.getServerPort());
            assertEquals(true, frameworkConfig.isServerRateLimitEnabled());
            assertEquals("defaultValue", frameworkConfig.getServerDegradationPolicy());
            assertEquals(true, frameworkConfig.isRateLimitEnabled());
            assertEquals("defaultValue", frameworkConfig.getConsumerDegradationPolicy());
            assertEquals(List.of("providerRateLimit", "providerMdc"), frameworkConfig.getProviderFilters());
            assertEquals(1, frameworkConfig.getMethodConfigs().size());

            MethodConfig methodConfig = frameworkConfig.getMethodConfigs().get(0);
            assertEquals("com.rpc.spring.boot.support.DemoService", methodConfig.getServiceName());
            assertEquals("hello", methodConfig.getMethodName());
            assertEquals(0, methodConfig.getRetryTimes());
            assertEquals(ClusterStrategy.FAIL_FAST, methodConfig.getClusterStrategy());
            assertEquals(1234, methodConfig.getReadTimeout());
            assertEquals("json", methodConfig.getSerializerName());
            assertEquals("roundRobin", methodConfig.getLoadBalancerName());
            assertEquals(true, methodConfig.getRateLimitEnabled());
            assertEquals(9, methodConfig.getRateLimitPermitsPerSecond());
            assertEquals(CircuitBreakerScope.METHOD, methodConfig.getCircuitBreakerScope());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestSupportConfiguration {
        @Bean
        CapturingRpcServer capturingRpcServer() {
            return new CapturingRpcServer();
        }

        @Bean
        RpcProviderBootstrap rpcProviderBootstrap(CapturingRpcServer rpcServer) throws Exception {
            Constructor<RpcProviderBootstrap> constructor =
                    RpcProviderBootstrap.class.getDeclaredConstructor(ServiceRegistry.class, RpcServer.class, RpcFrameworkConfig.class);
            constructor.setAccessible(true);
            return constructor.newInstance(null, rpcServer, new RpcFrameworkConfig());
        }

        @Bean
        RpcConsumerBootstrap rpcConsumerBootstrap() throws Exception {
            Constructor<RpcConsumerBootstrap> constructor =
                    RpcConsumerBootstrap.class.getDeclaredConstructor(ServiceDiscovery.class, RpcTransport.class);
            constructor.setAccessible(true);
            return constructor.newInstance(null, new NoopTransport());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConsumerBeanConfiguration {
        @Bean
        ConsumerBean consumerBean() {
            return new ConsumerBean();
        }
    }

    static class ConsumerBean {
        @RpcReference
        private DemoService demoService;
    }

    static class NoopTransport implements RpcTransport {
        @Override
        public RpcResponse sendRequest(RpcRequest rpcRequest) {
            return RpcResponse.success("ok", rpcRequest.getRequestId());
        }

        @Override
        public void sendRequestAsync(RpcRequest rpcRequest, long requestId) {
        }

        @Override
        public void close() {
        }
    }

    static class CapturingRpcServer implements RpcServer {
        private final CapturingLocalRegistry localRegistry = new CapturingLocalRegistry();

        @Override
        public void start() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public LocalRegistry getLocalRegistry() {
            return localRegistry;
        }
    }

    static class CapturingLocalRegistry implements LocalRegistry {
        private String registeredServiceName;
        private Object registeredServiceInstance;

        @Override
        public void register(String serviceName, Object serviceInstance) {
            this.registeredServiceName = serviceName;
            this.registeredServiceInstance = serviceInstance;
        }

        @Override
        public Object getService(String serviceName) {
            return registeredServiceInstance;
        }

        @Override
        public void unregister(String serviceName) {
        }

        @Override
        public boolean contains(String serviceName) {
            return false;
        }

        @Override
        public Iterable<String> serviceNames() {
            return List.of();
        }
    }
}

