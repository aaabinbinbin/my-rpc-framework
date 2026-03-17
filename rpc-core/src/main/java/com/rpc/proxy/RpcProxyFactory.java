package com.rpc.proxy;

import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * RPC 代理工厂（JDK 动态代理版本）
 */
@Slf4j
public class RpcProxyFactory {

    /**
     * 创建代理对象
     * @param serviceClass 服务接口类
     * @return 代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> serviceClass) {
        return (T) Proxy.newProxyInstance(
                serviceClass.getClassLoader(),
                new Class<?>[]{serviceClass}, // serviceClass可能是接口，所以不使用serviceClass.getInterfaces()
                new RpcInvocationHandler(serviceClass)
        );
    }

    /**
     * RPC 调用处理器
     */
    @Slf4j
    private static class RpcInvocationHandler implements InvocationHandler {

        private final Class<?> serviceClass;

        public RpcInvocationHandler(Class<?> serviceClass) {
            this.serviceClass = serviceClass;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 1. 构建 RPC 请求
            RpcRequest request = RpcRequest.builder()
                    .requestId(UUID.randomUUID().toString())
                    .serviceName(serviceClass.getName())
                    .methodName(method.getName())
                    .parameterTypes(method.getParameterTypes())
                    .parameters(args)
                    .returnType(method.getReturnType())
                    .build();
            log.info("[RPC] 准备调用：{}.{}", request.getServiceName(), request.getMethodName());
            log.debug("[RPC] 请求详情：{}", request);
            // 2. TODO: 发送远程请求（留待后续章节实现）
            // RpcResponse response = sendRemoteRequest(request);

            // 3. 暂时模拟响应
            RpcResponse response = mockResponse(request);

            // 4. 返回结果
            if (response.getCode() == 200) {
                return response.getData();
            } else {
                throw new RuntimeException("RPC 调用失败：" + response.getMessage());
            }
        }

        /**
         * 模拟响应（用于测试）
         */
        private RpcResponse mockResponse(RpcRequest request) {
            // 这里只是临时模拟，后续会实现真正的远程调用
            String mockResult = "Mock result for " + request.getMethodName() + " with params: ";
            if (request.getParameters() != null) {
                for (Object param : request.getParameters()) {
                    mockResult += param + ", ";
                }
            }
            return RpcResponse.success(mockResult, request.getRequestId());
        }
    }
}
