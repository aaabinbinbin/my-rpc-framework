package com.rpc.async;

import com.rpc.protocol.RpcRequest;
import com.rpc.transport.RpcTransport;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * 异步调用处理器
 * 根据方法返回值类型自动选择同步/异步模式
 *
 * 支持的返回类型：
 * - CompletableFuture<T> → 异步调用
 * - AsyncRpcResult<T> → 异步调用（封装版）
 * - 其他 → 同步调用（阻塞等待）
 */
@Slf4j
public class AsyncInvocationHandler implements InvocationHandler {
    private final Class<?> serviceClass;
    private final RpcTransport rpcClient;
    private final RequestManager requestManager;

    public AsyncInvocationHandler(Class<?> serviceClass,
                                  RpcTransport rpcClient,
                                  RequestManager requestManager) {
        this.serviceClass = serviceClass;
        this.rpcClient = rpcClient;
        this.requestManager = requestManager;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 1. 跳过 Object 类的方法
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 2. 构建 RPC 请求
        RpcRequest request = buildRpcRequest(method, args);

        // 3. 判断返回类型，决定调用模式
        Class<?> returnType = method.getReturnType();

        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            // CompletableFuture<T> → 异步调用
            log.debug("异步调用：{}.{}", request.getServiceName(), request.getMethodName());
            return invokeAsync(request, extractGenericType(returnType));

        } else if (AsyncRpcResult.class.isAssignableFrom(returnType)) {
            // AsyncRpcResult<T> → 异步调用（封装版）
            log.debug("异步调用（封装版）：{}.{}",
                    request.getServiceName(), request.getMethodName());
            return invokeAsyncResult(request, extractGenericType(returnType));

        } else {
            // 其他类型 → 同步调用（阻塞）
            log.debug("同步调用：{}.{}", request.getServiceName(), request.getMethodName());
            return invokeSync(request);
        }
    }

    /**
     * 构建 RPC 请求对象
     */
    private RpcRequest buildRpcRequest(Method method, Object[] args) {
        RpcRequest request = new RpcRequest();
        request.setServiceName(serviceClass.getName());
        request.setMethodName(method.getName());
        request.setParameterTypes(method.getParameterTypes());
        request.setParameters(args);
        request.setReturnType(method.getReturnType());
        return request;
    }

    /**
     * 同步调用（阻塞等待结果）
     */
    private Object invokeSync(RpcRequest request) throws Exception {
        // 调用现有的同步方法
        return rpcClient.sendRequest(request);
    }

    /**
     * 异步调用 - 返回 CompletableFuture<T>
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> invokeAsync(RpcRequest request, Class<?> resultType)
            throws Exception {
        // 生成请求 ID
        long requestId = generateRequestId();
        request.setRequestId(String.valueOf(requestId));

        // 创建 AsyncRpcResult
        @SuppressWarnings("rawtypes")
        AsyncRpcResult asyncResult = new AsyncRpcResult(resultType);

        // 注册到 RequestManager
        requestManager.addAsyncRequest(requestId, asyncResult);

        // 发送请求（非阻塞）
        rpcClient.sendRequestAsync(request, requestId);

        // 转换为 CompletableFuture 返回
        return (CompletableFuture<Object>) asyncResult.toCompletableFuture();
    }

    /**
     * 异步调用 - 返回 AsyncRpcResult<T>
     */
    @SuppressWarnings("unchecked")
    private AsyncRpcResult<Object> invokeAsyncResult(RpcRequest request, Class<?> resultType)
            throws Exception {
        // 生成请求 ID
        long requestId = generateRequestId();
        request.setRequestId(String.valueOf(requestId));

        // 创建 AsyncRpcResult
        @SuppressWarnings("rawtypes")
        AsyncRpcResult asyncResult = new AsyncRpcResult(resultType);

        // 注册到 RequestManager
        requestManager.addAsyncRequest(requestId, asyncResult);

        // 发送请求（非阻塞）
        rpcClient.sendRequestAsync(request, requestId);

        return (AsyncRpcResult<Object>) asyncResult;
    }

    /**
     * 生成唯一的请求 ID
     */
    private long generateRequestId() {
        return System.nanoTime() + Thread.currentThread().getId();
    }

    /**
     * 提取泛型类型（简化版，实际需要使用 Type 解析）
     */
    private Class<?> extractGenericType(Class<?> clazz) {
        // 简化处理：直接返回 Object
        // 完整实现需要解析 Method.getGenericReturnType()
        return Object.class;
    }
}
