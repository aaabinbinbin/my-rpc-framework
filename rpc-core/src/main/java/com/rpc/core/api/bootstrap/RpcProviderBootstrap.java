package com.rpc.core.api.bootstrap;

import com.rpc.core.api.annotation.RpcService;
import com.rpc.core.api.scanner.ClassPathScanner;
import com.rpc.core.config.RpcConfigLoader;
import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.invoke.filter.FilterManager;
import com.rpc.core.invoke.filter.FilterRuntimeConfigurator;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.registry.factory.ServiceRegistryFactory;
import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.factory.RpcServerFactory;
import com.rpc.core.transport.netty.server.config.RpcServerConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 高层服务提供端启动器，
 * 负责组装注册中心、服务端以及注解服务的发布流程。
 */
public class RpcProviderBootstrap implements AutoCloseable {
    private final ServiceRegistry serviceRegistry;
    private final RpcServer rpcServer;
    private final RpcFrameworkConfig frameworkConfig;

    /**
     * 避免在 start() 过程中重复扫描并注册配置里的服务。
     */
    private boolean configuredServicesRegistered;

    private RpcProviderBootstrap(ServiceRegistry serviceRegistry, RpcServer rpcServer, RpcFrameworkConfig frameworkConfig) {
        this.serviceRegistry = serviceRegistry;
        this.rpcServer = rpcServer;
        this.frameworkConfig = frameworkConfig;
    }

    public static RpcProviderBootstrap fromConfig() {
        return fromConfig(RpcConfigLoader.load());
    }

    public static RpcProviderBootstrap fromConfig(RpcFrameworkConfig frameworkConfig) {
        // 服务提供端启动顺序与消费端保持一致：
        // 先初始化治理和过滤器运行态，再创建注册中心与服务端。
        DegradationPolicy degradationPolicy = DegradationPolicyFactory.create(
                frameworkConfig.getServerDegradationPolicy(),
                frameworkConfig.getServerDegradationDefaultValues()
        );
        FilterManager.configure(frameworkConfig);
        FilterRuntimeConfigurator.configureProvider(frameworkConfig, degradationPolicy);

        ServiceRegistry serviceRegistry = ServiceRegistryFactory.create(frameworkConfig);
        RpcServerConfig serverConfig = RpcServerConfig.custom()
                .transportType(frameworkConfig.getTransportType())
                .host(frameworkConfig.getServerHost())
                .port(frameworkConfig.getServerPort())
                .bossThreads(frameworkConfig.getBossThreads())
                .workerThreads(frameworkConfig.getWorkerThreads())
                .bizCoreThreads(frameworkConfig.getBizCoreThreads())
                .bizMaxThreads(frameworkConfig.getBizMaxThreads())
                .bizQueueCapacity(frameworkConfig.getBizQueueCapacity())
                .shutdownTimeout(frameworkConfig.getShutdownTimeout())
                .readerIdleTime(frameworkConfig.getServerReaderIdleTime())
                .writerIdleTime(frameworkConfig.getServerWriterIdleTime())
                .allIdleTime(frameworkConfig.getServerAllIdleTime());
        RpcServer rpcServer = RpcServerFactory.create(serverConfig, serviceRegistry);
        return new RpcProviderBootstrap(serviceRegistry, rpcServer, frameworkConfig);
    }

    public RpcProviderBootstrap registerService(Class<?> serviceInterface, Object serviceImpl) {
        rpcServer.getLocalRegistry().register(serviceInterface.getName(), serviceImpl);
        return this;
    }

    public RpcProviderBootstrap registerAnnotatedServices(String... basePackages) {
        Set<String> uniquePackages = new LinkedHashSet<>();
        for (String basePackage : basePackages) {
            if (basePackage != null && !basePackage.isBlank()) {
                uniquePackages.add(basePackage.trim());
            }
        }
        for (String basePackage : uniquePackages) {
            // 服务提供端扫描只负责找出哪些类属于 RPC 服务。
            // 真正的服务暴露仍然统一收敛到 registerService。
            List<Class<?>> candidates = ClassPathScanner.scan(basePackage);
            for (Class<?> candidate : candidates) {
                RpcService rpcService = candidate.getAnnotation(RpcService.class);
                if (rpcService == null || candidate.isInterface() || Modifier.isAbstract(candidate.getModifiers())) {
                    continue;
                }
                Class<?> serviceInterface = resolveServiceInterface(candidate, rpcService);
                registerService(serviceInterface, instantiate(candidate));
            }
        }
        return this;
    }

    public RpcProviderBootstrap registerConfiguredServices() {
        configuredServicesRegistered = true;
        return registerAnnotatedServices(frameworkConfig.getServerScanPackages().toArray(String[]::new));
    }

    public void start() throws Exception {
        // 服务提供端启动支持两种模式：
        // 1. 在 start() 前显式调用 registerService(...)
        // 2. 根据配置自动注册注解服务
        if (frameworkConfig.isServerAutoRegisterAnnotatedServices() && !configuredServicesRegistered) {
            registerConfiguredServices();
        }
        rpcServer.start();
    }

    public static RpcProviderBootstrap startFromConfig() throws Exception {
        RpcProviderBootstrap bootstrap = fromConfig();
        bootstrap.start();
        return bootstrap;
    }

    @Override
    public void close() {
        rpcServer.shutdown();
        if (serviceRegistry != null) {
            serviceRegistry.close();
        }
    }

    private Object instantiate(Class<?> candidate) {
        try {
            Constructor<?> constructor = candidate.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate rpc service: " + candidate.getName(), e);
        }
    }

    private Class<?> resolveServiceInterface(Class<?> candidate, RpcService rpcService) {
        if (rpcService.value() != Void.class) {
            return rpcService.value();
        }
        // 如果注解里没有显式声明接口，则要求实现类只实现一个接口，
        // 这样导出的服务契约才不会产生歧义。
        Class<?>[] interfaces = candidate.getInterfaces();
        if (interfaces.length == 0) {
            throw new IllegalStateException("RpcService must declare an interface: " + candidate.getName());
        }
        if (interfaces.length > 1) {
            throw new IllegalStateException(
                    "RpcService must declare interface explicitly when multiple interfaces exist: "
                            + candidate.getName());
        }
        return interfaces[0];
    }
}
