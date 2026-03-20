package com.rpc.proxy;

import com.rpc.netty.RpcNettyClient;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
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
                new RpcInvocationHandler(serviceClass, host, port)
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
        enhancer.setCallback(new RpcMethodInterceptor(serviceClass, host, port));
        T proxy = (T) enhancer.create();
        return proxy;
    }

    /**
     * RPC 调用处理器 (JDK 实现动态代理)
     */
    @Slf4j
    private static class RpcInvocationHandler implements InvocationHandler {

        private final Class<?> serviceClass;
        private final String host;
        private final int port;

        public RpcInvocationHandler(Class<?> serviceClass, String host, int port) {
            this.serviceClass = serviceClass;
            this.host = host;
            this.port = port;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 1. 跳过 Object 类的方法
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }
            // 2. 构建 RPC 请求
            RpcRequest request = new RpcRequest();
            request.setServiceName(serviceClass.getName());
            request.setMethodName(method.getName());
            request.setParameterTypes(method.getParameterTypes());
            request.setParameters(args);
            request.setReturnType(method.getReturnType());
            log.info("准备调用：{}.{}", request.getServiceName(),
                    request.getMethodName());
            // 3. 发送远程请求
            if (client == null) {
                throw new IllegalStateException("RPC 客户端未初始化");
            }
            RpcResponse response = client.sendRequest(request, host, port);
            // 4. 返回结果
            if (response.getCode() == 200) {
                return response.getData();
            } else {
                throw new RuntimeException("RPC 调用失败：" + response.getMessage());
            }
        }
    }

    /**
     * RPC 调用处理器 (CGLib 实现动态代理)
     */
    private static class RpcMethodInterceptor implements MethodInterceptor {
        private final Class<?> serviceClass;
        private final String host;
        private final int port;

        public RpcMethodInterceptor(Class<?> serviceClass, String host, int port) {
            this.serviceClass = serviceClass;
            this.host = host;
            this.port = port;
        }

        @Override
        public Object intercept(Object o, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
            // 1. 跳过 Object 类的方法
            if (method.getDeclaringClass() == Object.class) {
                return methodProxy.invokeSuper(o, args);
            }
            // 2. 构建 RPC 请求
            RpcRequest request = new RpcRequest();
            request.setServiceName(serviceClass.getName());
            request.setMethodName(method.getName());
            request.setParameterTypes(method.getParameterTypes());
            request.setParameters(args);
            request.setReturnType(method.getReturnType());
            log.info("准备调用：{}.{}", request.getServiceName(),
                    request.getMethodName());
            // 3. 发送远程请求
            if (client == null) {
                throw new IllegalStateException("RPC 客户端未初始化");
            }
            RpcResponse response = client.sendRequest(request, host, port);
            // 4. 返回结果
            if (response.getCode() == 200) {
                return response.getData();
            } else {
                throw new RuntimeException("RPC 调用失败：" + response.getMessage());
            }
        }
    }
}
