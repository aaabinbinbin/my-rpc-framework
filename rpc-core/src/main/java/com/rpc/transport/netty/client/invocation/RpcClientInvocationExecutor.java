package com.rpc.transport.netty.client.invocation;

import com.rpc.common.constant.ErrorCode;
import com.rpc.common.exception.RpcException;
import com.rpc.faulttolerance.CircuitBreaker;
import com.rpc.faulttolerance.DegradationPolicy;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerManager;
import com.rpc.faulttolerance.retry.RetryExecutor;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class RpcClientInvocationExecutor {
    private final RpcServiceResolver serviceResolver;
    private final CircuitBreakerManager circuitBreakerManager;
    private final RetryExecutor retryExecutor;
    private final DegradationPolicy degradationPolicy;
    private final boolean enableDegradation;
    private final int degradationFailureThreshold;
    private final ConcurrentHashMap<String, AtomicInteger> failureCounters = new ConcurrentHashMap<>();

    public RpcClientInvocationExecutor(RpcServiceResolver serviceResolver,
                                       CircuitBreakerManager circuitBreakerManager,
                                       RetryExecutor retryExecutor,
                                       DegradationPolicy degradationPolicy,
                                       boolean enableDegradation,
                                       int degradationFailureThreshold) {
        this.serviceResolver = serviceResolver;
        this.circuitBreakerManager = circuitBreakerManager;
        this.retryExecutor = retryExecutor;
        this.degradationPolicy = degradationPolicy;
        this.enableDegradation = enableDegradation;
        this.degradationFailureThreshold = degradationFailureThreshold;
    }

    public RpcResponse execute(RpcRequest rpcRequest, RpcTransportInvoker transportInvoker) throws Exception {
        String serviceName = rpcRequest.getServiceName();
        if (enableDegradation && shouldDegrade(serviceName)) {
            return applyDegradation(rpcRequest);
        }

        try {
            RpcResponse response = retryExecutor.executeWithRetry(rpcRequest, invokeOnce(rpcRequest, transportInvoker));
            resetFailureCount(serviceName);
            return response;
        } catch (RpcException e) {
            circuitBreakerManager.getServiceCircuitBreaker(serviceName).recordFailure();
            int failures = incrementFailureCount(serviceName);
            log.debug("服务调用失败次数: {}/{}", failures, degradationFailureThreshold);
            throw e;
        } catch (Exception e) {
            circuitBreakerManager.getServiceCircuitBreaker(serviceName).recordFailure();
            incrementFailureCount(serviceName);
            throw new RpcException(ErrorCode.SERVER_ERROR, "RPC 调用失败: " + e.getMessage(), e);
        }
    }

    public InetSocketAddress resolveServiceAddress(String serviceName) throws RpcException {
        return serviceResolver.resolve(serviceName);
    }

    private Callable<RpcResponse> invokeOnce(RpcRequest rpcRequest, RpcTransportInvoker transportInvoker) {
        return () -> {
            InetSocketAddress address = serviceResolver.resolve(rpcRequest.getServiceName());
            RpcResponse response = transportInvoker.invoke(rpcRequest, address);
            if (response.getCode() == null || response.getCode() != 200) {
                throw new RpcException(ErrorCode.SERVICE_EXCEPTION, "RPC 调用失败: " + response.getMessage());
            }

            CircuitBreaker instanceCircuitBreaker = circuitBreakerManager.getInstanceCircuitBreaker(
                    rpcRequest.getServiceName(), address);
            instanceCircuitBreaker.recordSuccess();
            return response;
        };
    }

    private RpcResponse applyDegradation(RpcRequest rpcRequest) throws RpcException {
        String serviceName = rpcRequest.getServiceName();
        log.warn("触发降级策略: {}", serviceName);
        incrementFailureCount(serviceName);
        if (degradationPolicy != null) {
            return degradationPolicy.degrade(
                    rpcRequest,
                    new RuntimeException("服务不可用，已触发降级"));
        }
        throw new RpcException(
                ErrorCode.SERVICE_DEGRADED,
                "服务不可用，已触发降级但未配置降级策略");
    }

    private boolean shouldDegrade(String serviceName) {
        CircuitBreaker circuitBreaker = circuitBreakerManager.getServiceCircuitBreaker(serviceName);
        if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
            log.debug("服务熔断器已打开，触发降级: {}", serviceName);
            return true;
        }

        int failures = getFailureCount(serviceName);
        if (failures >= degradationFailureThreshold) {
            log.warn("连续失败达到阈值，触发降级: {} ({}/{})",
                    serviceName, failures, degradationFailureThreshold);
            return true;
        }
        return false;
    }

    private int getFailureCount(String serviceName) {
        return failureCounters.computeIfAbsent(serviceName, key -> new AtomicInteger()).get();
    }

    private int incrementFailureCount(String serviceName) {
        return failureCounters.computeIfAbsent(serviceName, key -> new AtomicInteger()).incrementAndGet();
    }

    private void resetFailureCount(String serviceName) {
        AtomicInteger counter = failureCounters.get(serviceName);
        if (counter == null) {
            return;
        }

        int previousFailures = counter.getAndSet(0);
        if (previousFailures > 0) {
            log.info("服务恢复，重置失败计数: {} (之前失败次数={})", serviceName, previousFailures);
        }
    }
}
