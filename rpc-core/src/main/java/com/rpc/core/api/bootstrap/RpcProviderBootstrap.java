package com.rpc.core.api.bootstrap;

import com.rpc.core.api.annotation.RpcService;
import com.rpc.core.api.scanner.ClassPathScanner;
import com.rpc.core.config.framework.RpcConfigLoader;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.invoke.filter.runtime.FilterManager;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfig;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfigurator;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.registry.factory.ServiceRegistryFactory;
import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.factory.RpcServerFactory;
import com.rpc.core.config.server.RpcServerConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * provider 侧启动门面。
 *
 * 所处阶段：应用启动时发布本地服务并启动 RPC server。
 * 主要职责：
 * - 加载框架配置并初始化 provider 侧 filter/runtime。
 * - 创建服务注册器、RpcServer 和本地服务注册表。
 * - 支持手动注册服务，也支持扫描 @RpcService 自动注册服务。
 * - 启动 server 后对外暴露服务，并在关闭时释放注册中心和 server 资源。
 *
 * 注意事项：
 * - Spring 场景下服务 Bean 已经由容器管理，通常会关闭 core 层自动扫描，避免重复注册。
 * - ACTIVE_PROVIDERS 用于避免多个 provider 共存时提前清空 provider 侧全局 filter 状态。
 */
public class RpcProviderBootstrap implements AutoCloseable {
    private static final AtomicInteger ACTIVE_PROVIDERS = new AtomicInteger(0);

    private final ServiceRegistry serviceRegistry;
    private final RpcServer rpcServer;
    private final RpcFrameworkConfig frameworkConfig;
    private boolean configuredServicesRegistered;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private RpcProviderBootstrap(ServiceRegistry serviceRegistry, RpcServer rpcServer, RpcFrameworkConfig frameworkConfig) {
        this.serviceRegistry = serviceRegistry;
        this.rpcServer = rpcServer;
        this.frameworkConfig = frameworkConfig;
        ACTIVE_PROVIDERS.incrementAndGet();
    }

    public static RpcProviderBootstrap fromConfig() {
        return fromConfig(RpcConfigLoader.load());
    }

    /** 按给定框架配置创建 provider 运行时，但不立即启动 server。 */
    public static RpcProviderBootstrap fromConfig(RpcFrameworkConfig frameworkConfig) {
        DegradationPolicy degradationPolicy = DegradationPolicyFactory.create(
                frameworkConfig.getServerDegradationPolicy(),
                frameworkConfig.getServerDegradationDefaultValues()
        );
        FilterManager.configure(frameworkConfig);
        FilterRuntimeConfigurator.configureProvider(frameworkConfig, degradationPolicy);

        ServiceRegistry serviceRegistry = ServiceRegistryFactory.create(frameworkConfig);
        RpcServerConfig serverConfig = RpcServerConfig.fromFrameworkConfig(frameworkConfig);
        RpcServer rpcServer = RpcServerFactory.create(serverConfig, serviceRegistry);
        return new RpcProviderBootstrap(serviceRegistry, rpcServer, frameworkConfig);
    }

    /** 手动注册一个服务接口和实现对象到 provider 本地注册表。 */
    public RpcProviderBootstrap registerService(Class<?> serviceInterface, Object serviceImpl) {
        rpcServer.getLocalRegistry().register(serviceInterface.getName(), serviceImpl);
        return this;
    }

    /**
     * 扫描指定包下的 @RpcService 类并注册。
     *
     * 非 Spring 场景会直接反射创建服务实现对象；
     * Spring 场景应优先复用容器里的 Bean，避免绕开依赖注入。
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

    /** 按配置中的 serverScanPackages 扫描并注册注解服务。 */
    public RpcProviderBootstrap registerConfiguredServices() {
        configuredServicesRegistered = true;
        return registerAnnotatedServices(frameworkConfig.getServerScanPackages().toArray(String[]::new));
    }

    /** 启动 provider server；如启用自动扫描，会先注册配置包下的 @RpcService。 */
    public void start() throws Exception {
        if (frameworkConfig.isServerAutoRegisterAnnotatedServices() && !configuredServicesRegistered) {
            registerConfiguredServices();
        }
        rpcServer.start();
    }

    /** 一步式创建并启动 provider bootstrap。 */
    public static RpcProviderBootstrap startFromConfig() throws Exception {
        RpcProviderBootstrap bootstrap = fromConfig();
        bootstrap.start();
        return bootstrap;
    }

    /** 关闭 provider server、清理 provider filter/runtime 状态，并关闭注册中心资源。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        rpcServer.shutdown();
        if (ACTIVE_PROVIDERS.decrementAndGet() == 0) {
            FilterRuntimeConfig.resetProvider();
        }
        if (serviceRegistry != null) {
            serviceRegistry.close();
        }
    }

    /** 非 Spring 场景下反射创建 @RpcService 实现对象。 */
    private Object instantiate(Class<?> candidate) {
        try {
            Constructor<?> constructor = candidate.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate rpc service: " + candidate.getName(), e);
        }
    }

    /** 解析 @RpcService 对应的服务接口；多接口实现时必须显式指定，避免服务名歧义。 */
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
