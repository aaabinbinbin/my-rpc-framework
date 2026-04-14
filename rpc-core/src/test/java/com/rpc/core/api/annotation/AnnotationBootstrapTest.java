package com.rpc.core.api.annotation;

import com.rpc.core.api.annotation.support.DemoService;
import com.rpc.core.api.bootstrap.RpcConsumerBootstrap;
import com.rpc.core.api.bootstrap.RpcProviderBootstrap;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.RpcTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：注解启动测试")
class AnnotationBootstrapTest {
    @DisplayName("验证 @RpcReference 字段会被注入为 RPC 代理对象")
    @Test
    void shouldInjectRpcReferenceField() throws Exception {
        try (RpcConsumerBootstrap bootstrap = instantiateConsumerBootstrap()) {
            ConsumerHolder holder = bootstrap.injectReferences(new ConsumerHolder());
            assertNotNull(holder.demoService);
        }
    }

    @DisplayName("验证父类中的 @RpcReference 字段也能完成代理注入")
    @Test
    void shouldInjectRpcReferenceFieldFromSuperclass() throws Exception {
        try (RpcConsumerBootstrap bootstrap = instantiateConsumerBootstrap()) {
            ExtendedConsumerHolder holder = bootstrap.injectReferences(new ExtendedConsumerHolder());
            assertNotNull(holder.demoService);
        }
    }

    @DisplayName("验证注解模式可以创建消费端应用实例")
    @Test
    void shouldCreateConsumerApplicationInstance() throws Exception {
        try (RpcConsumerBootstrap bootstrap = instantiateConsumerBootstrap()) {
            ConsumerHolder holder = bootstrap.createApplication(ConsumerHolder.class);
            assertNotNull(holder.demoService);
        }
    }

    @DisplayName("验证按包扫描可以注册 @RpcService 服务实现")
    @Test
    void shouldRegisterAnnotatedServiceByPackageScan() throws Exception {
        CapturingRpcServer rpcServer = new CapturingRpcServer();
        try (RpcProviderBootstrap bootstrap = instantiateProviderBootstrap(rpcServer)) {
            bootstrap.registerAnnotatedServices("com.rpc.core.api.annotation.support");

            assertSame(DemoService.class.getName(), rpcServer.localRegistry.registeredServiceName);
            assertNotNull(rpcServer.localRegistry.registeredServiceInstance);
        }
    }

    @DisplayName("验证配置指定的注解服务可以被注册")
    @Test
    void shouldRegisterConfiguredAnnotatedServices() throws Exception {
        CapturingRpcServer rpcServer = new CapturingRpcServer();
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setServerScanPackages(List.of("com.rpc.core.api.annotation.support"));
        try (RpcProviderBootstrap bootstrap = instantiateProviderBootstrap(rpcServer, frameworkConfig)) {
            bootstrap.registerConfiguredServices();

            assertSame(DemoService.class.getName(), rpcServer.localRegistry.registeredServiceName);
            assertNotNull(rpcServer.localRegistry.registeredServiceInstance);
        }
    }

    @DisplayName("验证启动时会自动注册配置中的注解服务")
    @Test
    void shouldAutoRegisterConfiguredServicesOnStart() throws Exception {
        CapturingRpcServer rpcServer = new CapturingRpcServer();
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setServerScanPackages(List.of("com.rpc.core.api.annotation.support"));
        frameworkConfig.setServerAutoRegisterAnnotatedServices(true);
        try (RpcProviderBootstrap bootstrap = instantiateProviderBootstrap(rpcServer, frameworkConfig)) {
            bootstrap.start();

            assertSame(DemoService.class.getName(), rpcServer.localRegistry.registeredServiceName);
            assertNotNull(rpcServer.localRegistry.registeredServiceInstance);
        }
    }

    @DisplayName("验证抽象消费端应用类会被拒绝")
    @Test
    void shouldRejectAbstractConsumerApplication() throws Exception {
        try (RpcConsumerBootstrap bootstrap = instantiateConsumerBootstrap()) {
            assertThrows(IllegalStateException.class, () -> bootstrap.createApplication(AbstractConsumerHolder.class));
        }
    }

    private RpcConsumerBootstrap instantiateConsumerBootstrap() throws Exception {
        Constructor<RpcConsumerBootstrap> constructor =
                RpcConsumerBootstrap.class.getDeclaredConstructor(com.rpc.core.discovery.ServiceDiscovery.class, RpcTransport.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, new NoopTransport());
    }

    private RpcProviderBootstrap instantiateProviderBootstrap(CapturingRpcServer rpcServer) throws Exception {
        return instantiateProviderBootstrap(rpcServer, new RpcFrameworkConfig());
    }

    private RpcProviderBootstrap instantiateProviderBootstrap(CapturingRpcServer rpcServer, RpcFrameworkConfig frameworkConfig) throws Exception {
        Constructor<RpcProviderBootstrap> constructor =
                RpcProviderBootstrap.class.getDeclaredConstructor(com.rpc.core.registry.ServiceRegistry.class, RpcServer.class, RpcFrameworkConfig.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, rpcServer, frameworkConfig);
    }

    static class ConsumerHolder {
        @RpcReference
        DemoService demoService;
    }

    static class ExtendedConsumerHolder extends ConsumerHolder {
    }

    abstract static class AbstractConsumerHolder extends ConsumerHolder {
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
            return java.util.List.of();
        }
    }
}

