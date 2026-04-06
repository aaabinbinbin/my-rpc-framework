package com.rpc.core.api.bootstrap;

import com.rpc.core.api.annotation.RpcReference;
import com.rpc.core.config.RpcClientConfig;
import com.rpc.core.config.RpcConfigLoader;
import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.extension.loadbalance.factory.LoadBalancerFactory;
import com.rpc.core.invoke.filter.FilterManager;
import com.rpc.core.invoke.filter.FilterRuntimeConfigurator;
import com.rpc.core.invoke.proxy.RpcProxyFactory;
import com.rpc.core.registry.factory.ServiceRegistryFactory;
import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import com.rpc.core.transport.RpcTransport;
import com.rpc.core.transport.factory.RpcTransportFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * consumer 侧的高层启动入口。
 *
 * 这个类的职责不是执行某一次具体调用，而是在应用启动阶段把 consumer 所需的基础设施组装好，
 * 包括：服务发现、传输层、过滤器运行时配置、降级策略、代理工厂等。
 *
 * 业务代码通常不会直接自己拼装 RpcTransport 或 ServiceDiscovery，
 * 而是通过这个类一次性拿到一个可工作的 consumer 运行环境。
 */
public class RpcConsumerBootstrap implements AutoCloseable {
    /** 服务发现组件，负责从注册中心拿到 provider 地址。 */
    private final ServiceDiscovery serviceDiscovery;

    /** 传输层客户端，负责真正把 RpcRequest 发送到远端。 */
    private final RpcTransport rpcTransport;

    /** 代理工厂，负责把接口类型转换成可发起远程调用的代理对象。 */
    private final RpcProxyFactory proxyFactory;

    private RpcConsumerBootstrap(ServiceDiscovery serviceDiscovery, RpcTransport rpcTransport) {
        this.serviceDiscovery = serviceDiscovery;
        this.rpcTransport = rpcTransport;
        this.proxyFactory = RpcProxyFactory.create(rpcTransport);
    }

    /** 使用默认配置文件创建 consumer bootstrap。 */
    public static RpcConsumerBootstrap fromConfig() {
        return fromConfig(RpcConfigLoader.load());
    }

    /**
     * 根据框架总配置创建 consumer bootstrap。
     *
     * 顺序上可以理解为：
     * 1. 先准备治理和过滤器运行时环境。
     * 2. 再准备服务发现。
     * 3. 再准备客户端传输层。
     * 4. 最后由构造方法内部准备代理工厂。
     */
    public static RpcConsumerBootstrap fromConfig(RpcFrameworkConfig frameworkConfig) {
        DegradationPolicy degradationPolicy = DegradationPolicyFactory.create(
                frameworkConfig.getConsumerDegradationPolicy(),
                frameworkConfig.getConsumerDegradationDefaultValues()
        );
        FilterManager.configure(frameworkConfig);
        FilterRuntimeConfigurator.configureConsumer(frameworkConfig, degradationPolicy);

        ServiceDiscovery serviceDiscovery = ServiceRegistryFactory.createDiscovery(frameworkConfig);
        RpcClientConfig clientConfig = RpcClientConfig.builder()
                .transportType(frameworkConfig.getTransportType())
                .connectTimeout(frameworkConfig.getConnectTimeout())
                .readTimeout(frameworkConfig.getReadTimeout())
                .heartbeatInterval(frameworkConfig.getHeartbeatInterval())
                .writerIdleTime(frameworkConfig.getWriterIdleTime())
                .readerIdleTime(frameworkConfig.getReaderIdleTime())
                .retryTimes(frameworkConfig.getRetryTimes())
                .clusterStrategy(frameworkConfig.getClusterStrategy())
                .methodConfigs(frameworkConfig.getMethodConfigs())
                .reconnectEnabled(frameworkConfig.isReconnectEnabled())
                .reconnectMaxRetryTimes(frameworkConfig.getReconnectMaxRetryTimes())
                .reconnectInitialDelaySeconds(frameworkConfig.getReconnectInitialDelaySeconds())
                .reconnectMaxDelaySeconds(frameworkConfig.getReconnectMaxDelaySeconds())
                .reconnectJitterEnabled(frameworkConfig.isReconnectJitterEnabled())
                .reconnectJitterMinSeconds(frameworkConfig.getReconnectJitterMinSeconds())
                .reconnectJitterMaxSeconds(frameworkConfig.getReconnectJitterMaxSeconds())
                .discoveryPreheatEnabled(frameworkConfig.isDiscoveryPreheatEnabled())
                .discoveryPreheatServices(frameworkConfig.getDiscoveryPreheatServices())
                .discoveryCacheTtlMillis(frameworkConfig.getDiscoveryCacheTtlMillis())
                .discoveryAllowStaleOnFailure(frameworkConfig.isDiscoveryAllowStaleOnFailure())
                .degradationPolicy(degradationPolicy)
                .enableDegradation(frameworkConfig.isEnableDegradation())
                .degradationFailureThreshold(frameworkConfig.getDegradationFailureThreshold())
                .rateLimitEnabled(frameworkConfig.isRateLimitEnabled())
                .rateLimitPermitsPerSecond(frameworkConfig.getRateLimitPermitsPerSecond())
                .circuitBreakerFailureRateThreshold(frameworkConfig.getCircuitBreakerFailureRateThreshold())
                .circuitBreakerMinNumberOfCalls(frameworkConfig.getCircuitBreakerMinNumberOfCalls())
                .circuitBreakerWaitDurationInOpenStateMillis(frameworkConfig.getCircuitBreakerWaitDurationInOpenStateMillis())
                .circuitBreakerPermittedHalfOpenCalls(frameworkConfig.getCircuitBreakerPermittedHalfOpenCalls())
                .loadBalancer(LoadBalancerFactory.getLoadBalancer(frameworkConfig.getLoadBalancer()))
                .serializerName(frameworkConfig.getSerializer())
                .build();

        RpcTransport rpcTransport = RpcTransportFactory.create(clientConfig, serviceDiscovery);
        return new RpcConsumerBootstrap(serviceDiscovery, rpcTransport);
    }

    /**
     * 为某个服务接口创建代理对象。
     * 这是 consumer 最常见的入口方法，
     * 无论是 Spring 自动注入还是手动创建应用实例，最终都会走到这里。
     */
    public <T> T getService(Class<T> serviceClass) {
        return proxyFactory.createProxyInstance(serviceClass);
    }

    /**
     * 创建一个普通应用类实例，并自动给其中的 @RpcReference 字段注入代理。
     * 这个方法适合非 Spring 场景下快速构建一个 consumer 示例对象。
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

    /**
     * 扫描目标对象及其父类中的字段，把所有 @RpcReference 都替换成 RPC 代理对象。
     * 这段逻辑本质上和 Spring 场景下的 BeanPostProcessor 注入类似，
     * 只是这里作用在一个普通 Java 对象上。
     */
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

    /**
     * 使用默认配置创建 bootstrap，并立即构造目标应用实例。
     *
     * 如果中途创建失败，会主动关闭 bootstrap，避免底层客户端资源泄漏。
     */
    public static <T> T createFromConfig(Class<T> targetClass) {
        RpcConsumerBootstrap bootstrap = fromConfig();
        try {
            return bootstrap.createApplication(targetClass);
        } catch (RuntimeException e) {
            bootstrap.close();
            throw e;
        }
    }

    /** 关闭 consumer 侧传输层资源，例如连接池、事件循环组等。 */
    @Override
    public void close() {
        rpcTransport.close();
    }
}
