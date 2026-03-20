package com.rpc.proxy;

import com.rpc.transport.netty.client.RpcNettyClient;
import com.rpc.proxy.impl.RpcInvocationHandler;
import com.rpc.proxy.impl.RpcMethodInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;

import java.lang.reflect.Proxy;

/**
 * RPC 代理工厂
 */
@Slf4j
public class RpcProxyFactory {
    private static RpcNettyClient client;
    public static void initClient(RpcNettyClient rpcClient) {
        client = rpcClient;
    }

    /**
     * 创建代理对象
     */
    public static <T> T createProxy(Class<T> serviceClass) {
        return createProxy(serviceClass, "127.0.0.1", 8080);
    }

    /**
     * 创建代理对象
     * @param serviceClass 服务接口类
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> serviceClass, String host, int port) {
        if (serviceClass.isInterface()) {
            return createProxyBySDK(serviceClass, host, port);
        } else {
            return createProxyByCGLib(serviceClass, host, port);
        }

    }

    /**
     * 使用 SDK 创建代理对象
     * @param serviceClass 服务接口类
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxyBySDK(Class<T> serviceClass, String host, int port) {
        return (T) Proxy.newProxyInstance(
                serviceClass.getClassLoader(),
                new Class<?>[]{serviceClass}, // serviceClass 可能是接口，所以不使用 serviceClass.getInterfaces()
                new RpcInvocationHandler(serviceClass, host, port, client)
        );

    }

    /**
     * 使用 cgLib 创建代理对象
     * @param serviceClass 服务接口类
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxyByCGLib(Class<T> serviceClass, String host, int port) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(serviceClass);
        enhancer.setCallback(new RpcMethodInterceptor(serviceClass, host, port, client));
        T proxy = (T) enhancer.create();
        return proxy;
    }
}
