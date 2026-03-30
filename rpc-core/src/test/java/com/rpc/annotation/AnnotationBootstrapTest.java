package com.rpc.core.api.annotation;

import com.rpc.core.api.annotation.support.DemoService;
import com.rpc.core.api.bootstrap.RpcConsumerBootstrap;
import com.rpc.core.api.bootstrap.RpcProviderBootstrap;
import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.RpcTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnnotationBootstrapTest {
    @Test
    void shouldInjectRpcReferenceField() throws Exception {
        RpcConsumerBootstrap bootstrap = instantiateConsumerBootstrap();
        ConsumerHolder holder = bootstrap.injectReferences(new ConsumerHolder());
        assertNotNull(holder.demoService);
    }

    @Test
    void shouldInjectRpcReferenceFieldFromSuperclass() throws Exception {
        RpcConsumerBootstrap bootstrap = instantiateConsumerBootstrap();
        ExtendedConsumerHolder holder = bootstrap.injectReferences(new ExtendedConsumerHolder());
        assertNotNull(holder.demoService);
    }

    @Test
    void shouldCreateConsumerApplicationInstance() throws Exception {
        RpcConsumerBootstrap bootstrap = instantiateConsumerBootstrap();
        ConsumerHolder holder = bootstrap.createApplication(ConsumerHolder.class);
        assertNotNull(holder.demoService);
    }

    @Test
    void shouldRegisterAnnotatedServiceByPackageScan() throws Exception {
        CapturingRpcServer rpcServer = new CapturingRpcServer();
        RpcProviderBootstrap bootstrap = instantiateProviderBootstrap(rpcServer);

        bootstrap.registerAnnotatedServices("com.rpc.core.api.annotation.support");

        assertSame(DemoService.class.getName(), rpcServer.localRegistry.registeredServiceName);
        assertNotNull(rpcServer.localRegistry.registeredServiceInstance);
    }

    @Test
    void shouldRegisterConfiguredAnnotatedServices() throws Exception {
        CapturingRpcServer rpcServer = new CapturingRpcServer();
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setServerScanPackages(List.of("com.rpc.core.api.annotation.support"));
        RpcProviderBootstrap bootstrap = instantiateProviderBootstrap(rpcServer, frameworkConfig);

        bootstrap.registerConfiguredServices();

        assertSame(DemoService.class.getName(), rpcServer.localRegistry.registeredServiceName);
        assertNotNull(rpcServer.localRegistry.registeredServiceInstance);
    }

    @Test
    void shouldAutoRegisterConfiguredServicesOnStart() throws Exception {
        CapturingRpcServer rpcServer = new CapturingRpcServer();
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setServerScanPackages(List.of("com.rpc.core.api.annotation.support"));
        frameworkConfig.setServerAutoRegisterAnnotatedServices(true);
        RpcProviderBootstrap bootstrap = instantiateProviderBootstrap(rpcServer, frameworkConfig);

        bootstrap.start();

        assertSame(DemoService.class.getName(), rpcServer.localRegistry.registeredServiceName);
        assertNotNull(rpcServer.localRegistry.registeredServiceInstance);
    }

    @Test
    void shouldRejectAbstractConsumerApplication() throws Exception {
        RpcConsumerBootstrap bootstrap = instantiateConsumerBootstrap();
        assertThrows(IllegalStateException.class, () -> bootstrap.createApplication(AbstractConsumerHolder.class));
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
        public com.rpc.core.protocol.RpcResponse sendRequest(com.rpc.core.protocol.RpcRequest rpcRequest) {
            return com.rpc.core.protocol.RpcResponse.success("ok", rpcRequest.getRequestId());
        }

        @Override
        public void sendRequestAsync(com.rpc.core.protocol.RpcRequest rpcRequest, long requestId) {
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

