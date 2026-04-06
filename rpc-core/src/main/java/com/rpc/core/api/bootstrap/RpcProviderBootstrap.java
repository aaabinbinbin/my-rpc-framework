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
 * provider 侧的高层启动入口。
 *
 * 这个类负责组装服务注册中心、RPC 服务端、本地服务注册流程，
 * 是 provider 侧从“普通 Java 服务对象”到“可对外提供 RPC 调用”的总装配器。
 */
public class RpcProviderBootstrap implements AutoCloseable {
    /** 服务注册中心客户端，负责把当前 provider 地址注册到注册中心。 */
    private final ServiceRegistry serviceRegistry;

    /** RPC 服务端，负责监听端口、接收请求并调度到 provider 执行链。 */
    private final RpcServer rpcServer;

    /** 框架总配置，决定服务端的传输方式、端口、线程池、扫描包等策略。 */
    private final RpcFrameworkConfig frameworkConfig;

    /** 防止 start() 时重复扫描并注册配置中的服务。 */
    private boolean configuredServicesRegistered;

    private RpcProviderBootstrap(ServiceRegistry serviceRegistry, RpcServer rpcServer, RpcFrameworkConfig frameworkConfig) {
        this.serviceRegistry = serviceRegistry;
        this.rpcServer = rpcServer;
        this.frameworkConfig = frameworkConfig;
    }

    /** 使用默认配置文件创建 provider bootstrap。 */
    public static RpcProviderBootstrap fromConfig() {
        return fromConfig(RpcConfigLoader.load());
    }

    /**
     * 根据框架总配置创建 provider bootstrap。
     * 顺序可以理解为：
     * 1. 先准备 provider 侧过滤器和治理环境。
     * 2. 再创建服务注册中心客户端。
     * 3. 再创建真正监听端口的 RPC 服务端。
     */
    public static RpcProviderBootstrap fromConfig(RpcFrameworkConfig frameworkConfig) {
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

    /**
     * 把一个服务接口及其实现对象注册到本地注册表。
     * 注意这里注册的是 provider 本地 JVM 内部的映射关系，
     * 用于请求真正到达当前 provider 后快速找到该调用哪个对象。
     */
    public RpcProviderBootstrap registerService(Class<?> serviceInterface, Object serviceImpl) {
        rpcServer.getLocalRegistry().register(serviceInterface.getName(), serviceImpl);
        return this;
    }

    /**
     * 扫描指定包下的 @RpcService 类，并把它们注册为本地服务。
     * 这个方法主要用于非 Spring 或需要按包自动导出服务的场景。
     */
    public RpcProviderBootstrap registerAnnotatedServices(String... basePackages) {
        Set<String> uniquePackages = new LinkedHashSet<>();
        for (String basePackage : basePackages) {
            if (basePackage != null && !basePackage.isBlank()) {
                uniquePackages.add(basePackage.trim());
            }
        }
        for (String basePackage : uniquePackages) {
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

    /**
     * 按配置中的扫描包自动注册服务。
     * 这是对 registerAnnotatedServices 的一个配置化封装。
     */
    public RpcProviderBootstrap registerConfiguredServices() {
        configuredServicesRegistered = true;
        return registerAnnotatedServices(frameworkConfig.getServerScanPackages().toArray(String[]::new));
    }

    /**
     * 启动 provider。
     * 如果配置允许自动注册注解服务且当前尚未注册过，
     * 就先完成服务注册，再真正启动 RPC server 监听端口。
     */
    public void start() throws Exception {
        if (frameworkConfig.isServerAutoRegisterAnnotatedServices() && !configuredServicesRegistered) {
            registerConfiguredServices();
        }
        rpcServer.start();
    }

    /** 使用默认配置创建并立即启动 provider。 */
    public static RpcProviderBootstrap startFromConfig() throws Exception {
        RpcProviderBootstrap bootstrap = fromConfig();
        bootstrap.start();
        return bootstrap;
    }

    /** 关闭服务端和注册中心资源。 */
    @Override
    public void close() {
        rpcServer.shutdown();
        if (serviceRegistry != null) {
            serviceRegistry.close();
        }
    }

    /**
     * 通过无参构造方法创建服务实现对象。
     *
     * 这个分支主要用于框架自行扫描和实例化 @RpcService 类的场景。
     */
    private Object instantiate(Class<?> candidate) {
        try {
            Constructor<?> constructor = candidate.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate rpc service: " + candidate.getName(), e);
        }
    }

    /**
     * 推断 @RpcService 对应的服务接口。
     *
     * 如果注解没有显式声明接口，则要求实现类只能实现一个接口，
     * 否则导出服务时就无法判断到底应该以哪个接口作为服务契约。
     */
    private Class<?> resolveServiceInterface(Class<?> candidate, RpcService rpcService) {
        if (rpcService.value() != Void.class) {
            return rpcService.value();
        }
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
