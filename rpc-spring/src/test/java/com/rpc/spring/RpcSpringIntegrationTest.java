package com.rpc.spring;

import com.rpc.core.api.annotation.RpcReference;
import com.rpc.core.api.bootstrap.RpcConsumerBootstrap;
import com.rpc.core.api.bootstrap.RpcProviderBootstrap;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.spring.support.DemoService;
import com.rpc.spring.support.DemoServiceImpl;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.RpcTransport;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RpcSpringIntegrationTest {
    @Test
    void shouldRegisterRpcServiceBeansAndInjectRpcReference() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SpringTestConfig.class)) {
            ConsumerBean consumerBean = context.getBean(ConsumerBean.class);
            CapturingRpcServer rpcServer = context.getBean(CapturingRpcServer.class);

            assertNotNull(consumerBean.demoService);
            assertEquals(DemoService.class.getName(), rpcServer.localRegistry.registeredServiceName);
            assertNotNull(rpcServer.localRegistry.registeredServiceInstance);
            assertNotNull(context.getBean(DemoServiceImpl.class));
        }
    }

    @Configuration
    @EnableRpc(scanPackages = "com.rpc.spring.support")
    static class SpringTestConfig {
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

