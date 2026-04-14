package com.rpc.core.api.bootstrap;

import com.rpc.core.api.annotation.RpcReference;
import com.rpc.core.config.client.RpcClientConfig;
import com.rpc.core.config.framework.RpcConfigLoader;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.invoke.filter.runtime.FilterManager;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfig;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfigurator;
import com.rpc.core.invoke.proxy.RpcProxyFactory;
import com.rpc.core.registry.factory.ServiceRegistryFactory;
import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import com.rpc.core.transport.RpcTransport;
import com.rpc.core.transport.factory.RpcTransportFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * consumer 侧启动门面。
 *
 * 所处阶段：应用启动或测试代码需要创建 RPC consumer 运行时。
 * 主要职责：
 * - 加载框架配置并初始化 consumer 侧 filter/runtime。
 * - 创建注册发现客户端、Netty transport 和代理工厂。
 * - 为服务接口生成代理对象，或给普通对象注入 @RpcReference 字段。
 * - 关闭时释放 transport，并在最后一个 consumer 关闭后清理 consumer 侧全局运行时状态。
 *
 * 注意事项：
 * - 这里不直接发送请求，真实调用发生在代理对象的方法拦截之后。
 * - ACTIVE_CONSUMERS 用于避免多个 bootstrap 共存时提前重置全局 filter/熔断状态。
 */
public class RpcConsumerBootstrap implements AutoCloseable {
    private static final AtomicInteger ACTIVE_CONSUMERS = new AtomicInteger(0);

    private final ServiceDiscovery serviceDiscovery;
    private final RpcTransport rpcTransport;
    private final RpcProxyFactory proxyFactory;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private RpcConsumerBootstrap(ServiceDiscovery serviceDiscovery, RpcTransport rpcTransport) {
        this.serviceDiscovery = serviceDiscovery;
        this.rpcTransport = rpcTransport;
        this.proxyFactory = RpcProxyFactory.create(rpcTransport);
        ACTIVE_CONSUMERS.incrementAndGet();
    }

    public static RpcConsumerBootstrap fromConfig() {
        return fromConfig(RpcConfigLoader.load());
    }

    /** 按给定框架配置创建 consumer 运行时。 */
    public static RpcConsumerBootstrap fromConfig(RpcFrameworkConfig frameworkConfig) {
        DegradationPolicy degradationPolicy = DegradationPolicyFactory.create(
                frameworkConfig.getConsumerDegradationPolicy(),
                frameworkConfig.getConsumerDegradationDefaultValues()
        );
        FilterManager.configure(frameworkConfig);
        FilterRuntimeConfigurator.configureConsumer(frameworkConfig, degradationPolicy);

        ServiceDiscovery serviceDiscovery = ServiceRegistryFactory.createDiscovery(frameworkConfig);
        RpcClientConfig clientConfig = RpcClientConfig.fromFrameworkConfig(frameworkConfig, degradationPolicy);

        RpcTransport rpcTransport = RpcTransportFactory.create(clientConfig, serviceDiscovery);
        return new RpcConsumerBootstrap(serviceDiscovery, rpcTransport);
    }

    /** 为某个服务接口创建 RPC 代理对象，业务侧后续调用会进入代理拦截逻辑。 */
    public <T> T getService(Class<T> serviceClass) {
        return proxyFactory.createProxyInstance(serviceClass);
    }

    /**
     * 创建一个普通 consumer 应用对象并注入 @RpcReference。
     *
     * 主要用于非 Spring 场景；Spring 场景下由 RpcSpringManager 在 Bean 生命周期中完成注入。
     */
    public <T> T createApplication(Class<T> targetClass) {
        try {
            if (Modifier.isAbstract(targetClass.getModifiers()) || targetClass.isInterface()) {
                throw new IllegalStateException(
                        "Rpc consumer application must be a concrete class: " + targetClass.getName());
            }
            Constructor<T> constructor = targetClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return injectReferences(constructor.newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create rpc consumer application: " + targetClass.getName(), e);
        }
    }

    /** 扫描目标对象及其父类字段，把 @RpcReference 字段替换为 RPC 代理对象。 */
    public <T> T injectReferences(T target) {
        for (Class<?> current = target.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                RpcReference rpcReference = field.getAnnotation(RpcReference.class);
                if (rpcReference == null) {
                    continue;
                }
                Class<?> serviceType = rpcReference.value() == Void.class ? field.getType() : rpcReference.value();
                Object proxy = getService(serviceType);
                try {
                    field.setAccessible(true);
                    field.set(target, proxy);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Failed to inject rpc reference: " + field.getName(), e);
                }
            }
        }
        return target;
    }

    /** 一步式创建 consumer bootstrap 和目标应用对象；如果创建失败会主动关闭 bootstrap。 */
    public static <T> T createFromConfig(Class<T> targetClass) {
        RpcConsumerBootstrap bootstrap = fromConfig();
        try {
            return bootstrap.createApplication(targetClass);
        } catch (RuntimeException e) {
            bootstrap.close();
            throw e;
        }
    }

    /** 关闭 consumer 运行时资源，并在最后一个 consumer 关闭后清理全局 consumer 状态。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        rpcTransport.close();
        if (ACTIVE_CONSUMERS.decrementAndGet() == 0) {
            FilterRuntimeConfig.resetConsumer();
            FilterRuntimeConfig.getCircuitBreakerManager().resetAll();
        }
    }
}
