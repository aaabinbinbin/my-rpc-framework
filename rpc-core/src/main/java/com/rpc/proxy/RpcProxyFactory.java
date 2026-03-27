package com.rpc.proxy;

import com.rpc.config.RpcClientConfig;
import com.rpc.proxy.impl.RpcInvocationHandler;
import com.rpc.proxy.impl.RpcMethodInterceptor;
import com.rpc.registry.ServiceRegistry;
import com.rpc.transport.RpcTransport;
import com.rpc.transport.factory.RpcTransportFactory;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;

import java.lang.reflect.Proxy;

/**
 * RPC 代理工厂
 */
@Slf4j
public class RpcProxyFactory {
    private static RpcTransport client;

    public static void initClient(RpcTransport rpcClient) {
        client = rpcClient;
    }

    public static void initClient(RpcClientConfig config, ServiceRegistry serviceRegistry) {
        client = RpcTransportFactory.create(config, serviceRegistry);
    }

    /**
     * 创建代理对象
     * @param serviceClass 服务接口类
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> serviceClass) {
        if (serviceClass.isInterface()) {
            return createProxyBySDK(serviceClass);
        } else {
            return createProxyByCGLib(serviceClass);
        }

    }

    /**
     * 使用 SDK 创建代理对象
     * @param serviceClass 服务接口类
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxyBySDK(Class<T> serviceClass) {
        return (T) Proxy.newProxyInstance(
                serviceClass.getClassLoader(),
                new Class<?>[]{serviceClass}, // serviceClass 可能是接口，所以不使用 serviceClass.getInterfaces()
                new RpcInvocationHandler(serviceClass, client)
        );

    }

    /**
     * 使用 cgLib 创建代理对象
     * @param serviceClass 服务接口类
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxyByCGLib(Class<T> serviceClass) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(serviceClass);
        enhancer.setCallback(new RpcMethodInterceptor(serviceClass, client));
        T proxy = (T) enhancer.create();
        return proxy;
    }
}
