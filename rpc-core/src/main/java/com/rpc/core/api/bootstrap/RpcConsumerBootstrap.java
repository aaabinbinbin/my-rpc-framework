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
 * 消费端高层启动入口，负责组装服务发现、传输层和代理创建流程。
 * 外部代码通常应该直接依赖这个类，而不是手动拼装客户端内部组件。
 */
public class RpcConsumerBootstrap implements AutoCloseable {
    private final ServiceDiscovery serviceDiscovery;
    private final RpcTransport rpcTransport;
    private final RpcProxyFactory proxyFactory;

    private RpcConsumerBootstrap(ServiceDiscovery serviceDiscovery, RpcTransport rpcTransport) {
        this.serviceDiscovery = serviceDiscovery;
        this.rpcTransport = rpcTransport;
        this.proxyFactory = RpcProxyFactory.create(rpcTransport);
    }

    public static RpcConsumerBootstrap fromConfig() {
        return fromConfig(RpcConfigLoader.load());
    }

    public static RpcConsumerBootstrap fromConfig(RpcFrameworkConfig frameworkConfig) {
        // 消费端启动时，先把治理相关运行态灌入 filter（过滤器）/ runtime（运行时）配置，
        // 再按顺序创建 discovery（服务发现）、transport（传输）和 proxy（代理）。
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

        // transport（传输）只负责请求发送；bootstrap（启动器）在这里把
        // discovery（服务发现）、配置和 proxy（代理）一起组装，
        // 让外部使用方式保持简洁。
        RpcTransport rpcTransport = RpcTransportFactory.create(clientConfig, serviceDiscovery);
        return new RpcConsumerBootstrap(serviceDiscovery, rpcTransport);
    }

    public <T> T getService(Class<T> serviceClass) {
        return proxyFactory.createProxyInstance(serviceClass);
    }

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

    public <T> T injectReferences(T target) {
        for (Class<?> current = target.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                RpcReference rpcReference = field.getAnnotation(RpcReference.class);
                if (rpcReference == null) {
                    continue;
                }
                // @RpcReference 同时支持显式指定服务接口，
                // 以及默认使用字段类型作为服务契约。
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

    public static <T> T createFromConfig(Class<T> targetClass) {
        RpcConsumerBootstrap bootstrap = fromConfig();
        try {
            return bootstrap.createApplication(targetClass);
        } catch (RuntimeException e) {
            bootstrap.close();
            throw e;
        }
    }

    @Override
    public void close() {
        rpcTransport.close();
    }
}
