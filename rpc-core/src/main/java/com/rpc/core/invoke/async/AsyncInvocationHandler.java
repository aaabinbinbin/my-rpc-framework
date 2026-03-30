package com.rpc.core.invoke.async;

import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.transport.RpcTransport;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * 试验性异步代理路径使用的调用处理器。
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
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        RpcRequest request = buildRpcRequest(method, args);
        Class<?> returnType = method.getReturnType();

        if (CompletableFuture.class.isAssignableFrom(returnType)) {
            log.debug("Invoke async CompletableFuture call: {}.{}",
                    request.getServiceName(), request.getMethodName());
            return invokeAsync(request, extractGenericType(returnType));
        }

        if (AsyncRpcResult.class.isAssignableFrom(returnType)) {
            log.debug("Invoke async AsyncRpcResult call: {}.{}",
                    request.getServiceName(), request.getMethodName());
            return invokeAsyncResult(request, extractGenericType(returnType));
        }

        log.debug("Invoke sync call through async handler: {}.{}",
                request.getServiceName(), request.getMethodName());
        return invokeSync(request);
    }

    private RpcRequest buildRpcRequest(Method method, Object[] args) {
        RpcRequest request = new RpcRequest();
        request.setServiceName(serviceClass.getName());
        request.setMethodName(method.getName());
        request.setParameterTypes(method.getParameterTypes());
        request.setParameters(args);
        request.setReturnType(method.getReturnType());
        return request;
    }

    private Object invokeSync(RpcRequest request) throws Exception {
        return rpcClient.sendRequest(request);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> invokeAsync(RpcRequest request, Class<?> resultType) throws Exception {
        long requestId = generateRequestId();
        request.setRequestId(String.valueOf(requestId));

        @SuppressWarnings("rawtypes")
        AsyncRpcResult asyncResult = new AsyncRpcResult(resultType);
        requestManager.addAsyncRequest(requestId, asyncResult);
        rpcClient.sendRequestAsync(request, requestId);

        return (CompletableFuture<Object>) asyncResult.toCompletableFuture();
    }

    @SuppressWarnings("unchecked")
    private AsyncRpcResult<Object> invokeAsyncResult(RpcRequest request, Class<?> resultType) throws Exception {
        long requestId = generateRequestId();
        request.setRequestId(String.valueOf(requestId));

        @SuppressWarnings("rawtypes")
        AsyncRpcResult asyncResult = new AsyncRpcResult(resultType);
        requestManager.addAsyncRequest(requestId, asyncResult);
        rpcClient.sendRequestAsync(request, requestId);

        return (AsyncRpcResult<Object>) asyncResult;
    }

    private long generateRequestId() {
        return System.nanoTime() + Thread.currentThread().getId();
    }

    private Class<?> extractGenericType(Class<?> clazz) {
        return Object.class;
    }
}
