package com.rpc.core.invoke.proxy;

import com.rpc.core.config.RpcClientConfig;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.invoke.proxy.impl.RpcInvocationHandler;
import com.rpc.core.invoke.proxy.impl.RpcMethodInterceptor;
import com.rpc.core.transport.RpcTransport;
import com.rpc.core.transport.factory.RpcTransportFactory;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;

import java.lang.reflect.Proxy;

/**
 * RPC 客户端代理工厂。
 *
 * 这个类的目标很单一：
 * 把一个服务接口或服务类包装成“看起来像本地对象、实际会发起远程调用”的代理对象。
 *
 * 对于接口优先使用 JDK 动态代理，
 * 对于普通类则回退到 CGLIB 代理。
 */
@Slf4j
public class RpcProxyFactory {
    /**
     * 兼容旧调用方式的全局默认客户端。
     *
     * 新代码更推荐使用实例化工厂，并显式注入 RpcTransport，
     * 这样依赖关系更清晰，也更适合和 bootstrap 配合使用。
     */
    private static volatile RpcTransport defaultClient;

    /** 当前工厂绑定的客户端传输层。 */
    private final RpcTransport client;

    private RpcProxyFactory(RpcTransport client) {
        this.client = client;
    }

    /** 基于一个已准备好的 RpcTransport 创建代理工厂。 */
    public static RpcProxyFactory create(RpcTransport rpcClient) {
        return new RpcProxyFactory(rpcClient);
    }

    /** 基于配置和服务发现直接创建代理工厂。 */
    public static RpcProxyFactory create(RpcClientConfig config, ServiceDiscovery serviceDiscovery) {
        return new RpcProxyFactory(RpcTransportFactory.create(config, serviceDiscovery));
    }

    /** 初始化兼容模式下的全局默认客户端。 */
    public static void initClient(RpcTransport rpcClient) {
        defaultClient = rpcClient;
    }

    /** 使用配置和服务发现初始化兼容模式下的全局默认客户端。 */
    public static void initClient(RpcClientConfig config, ServiceDiscovery serviceDiscovery) {
        defaultClient = RpcTransportFactory.create(config, serviceDiscovery);
    }

    /**
     * 为指定服务类型创建代理实例。
     *
     * 接口优先使用 JDK 动态代理，因为这条路径更轻量；
     * 只有在服务类型本身是具体类时，才退回到 CGLIB。
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxyInstance(Class<T> serviceClass) {
        if (serviceClass.isInterface()) {
            return createProxyBySDKInstance(serviceClass);
        }
        return createProxyByCGLibInstance(serviceClass);
    }

    /**
     * 通过 JDK 动态代理创建服务代理。
     *
     * 代理后的每一次方法调用最终都会进入 RpcInvocationHandler，
     * 再被翻译成 RpcRequest 并发起远程调用。
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxyBySDKInstance(Class<T> serviceClass) {
        return (T) Proxy.newProxyInstance(
                serviceClass.getClassLoader(),
                new Class<?>[]{serviceClass},
                new RpcInvocationHandler(serviceClass, requireClient())
        );
    }

    /**
     * 通过 CGLIB 创建服务代理。
     *
     * 当服务类型不是接口而是具体类时，JDK 动态代理无法直接代理该类，
     * 这时需要通过继承方式生成代理子类。
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxyByCGLibInstance(Class<T> serviceClass) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(serviceClass);
        enhancer.setCallback(new RpcMethodInterceptor(serviceClass, requireClient()));
        return (T) enhancer.create();
    }

    /** 使用兼容模式下的默认客户端创建代理。 */
    public static <T> T createProxy(Class<T> serviceClass) {
        return usingDefault().createProxyInstance(serviceClass);
    }

    /** 使用兼容模式下的默认客户端创建 JDK 动态代理。 */
    public static <T> T createProxyBySDK(Class<T> serviceClass) {
        return usingDefault().createProxyBySDKInstance(serviceClass);
    }

    /** 使用兼容模式下的默认客户端创建 CGLIB 代理。 */
    public static <T> T createProxyByCGLib(Class<T> serviceClass) {
        return usingDefault().createProxyByCGLibInstance(serviceClass);
    }

    /** 使用全局默认客户端包装出一个临时工厂实例。 */
    private static RpcProxyFactory usingDefault() {
        return new RpcProxyFactory(requireDefaultClient());
    }

    /** 确保实例化工厂已经绑定了 RpcTransport。 */
    private RpcTransport requireClient() {
        if (client == null) {
            throw new IllegalStateException("RPC client is not initialized");
        }
        return client;
    }

    /** 确保兼容模式下的默认客户端已经初始化。 */
    private static RpcTransport requireDefaultClient() {
        if (defaultClient == null) {
            throw new IllegalStateException("RPC client is not initialized");
        }
        return defaultClient;
    }
}
