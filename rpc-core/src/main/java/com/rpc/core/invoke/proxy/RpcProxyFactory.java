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
 * 用于创建 RPC 客户端代理的工厂。
 */
@Slf4j
public class RpcProxyFactory {
    /**
     * 静态默认客户端只保留给旧调用方式做兼容。
     * 新代码应优先使用由启动层创建的实例工厂。
     */
    private static volatile RpcTransport defaultClient;

    private final RpcTransport client;

    private RpcProxyFactory(RpcTransport client) {
        this.client = client;
    }

    public static RpcProxyFactory create(RpcTransport rpcClient) {
        return new RpcProxyFactory(rpcClient);
    }

    public static RpcProxyFactory create(RpcClientConfig config, ServiceDiscovery serviceDiscovery) {
        return new RpcProxyFactory(RpcTransportFactory.create(config, serviceDiscovery));
    }

    public static void initClient(RpcTransport rpcClient) {
        defaultClient = rpcClient;
    }

    public static void initClient(RpcClientConfig config, ServiceDiscovery serviceDiscovery) {
        defaultClient = RpcTransportFactory.create(config, serviceDiscovery);
    }

    @SuppressWarnings("unchecked")
    public <T> T createProxyInstance(Class<T> serviceClass) {
        // 接口优先使用 JDK 动态代理；具体类则回退到 CGLIB。
        if (serviceClass.isInterface()) {
            return createProxyBySDKInstance(serviceClass);
        }
        return createProxyByCGLibInstance(serviceClass);
    }

    @SuppressWarnings("unchecked")
    public <T> T createProxyBySDKInstance(Class<T> serviceClass) {
        return (T) Proxy.newProxyInstance(
                serviceClass.getClassLoader(),
                new Class<?>[]{serviceClass},
                new RpcInvocationHandler(serviceClass, requireClient())
        );
    }

    @SuppressWarnings("unchecked")
    public <T> T createProxyByCGLibInstance(Class<T> serviceClass) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(serviceClass);
        enhancer.setCallback(new RpcMethodInterceptor(serviceClass, requireClient()));
        return (T) enhancer.create();
    }

    public static <T> T createProxy(Class<T> serviceClass) {
        return usingDefault().createProxyInstance(serviceClass);
    }

    public static <T> T createProxyBySDK(Class<T> serviceClass) {
        return usingDefault().createProxyBySDKInstance(serviceClass);
    }

    public static <T> T createProxyByCGLib(Class<T> serviceClass) {
        return usingDefault().createProxyByCGLibInstance(serviceClass);
    }

    private static RpcProxyFactory usingDefault() {
        return new RpcProxyFactory(requireDefaultClient());
    }

    private RpcTransport requireClient() {
        if (client == null) {
            throw new IllegalStateException("RPC client is not initialized");
        }
        return client;
    }

    private static RpcTransport requireDefaultClient() {
        if (defaultClient == null) {
            throw new IllegalStateException("RPC client is not initialized");
        }
        return defaultClient;
    }
}
