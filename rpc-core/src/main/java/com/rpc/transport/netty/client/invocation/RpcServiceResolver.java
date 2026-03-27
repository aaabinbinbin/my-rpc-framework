package com.rpc.transport.netty.client.invocation;

import com.rpc.common.constant.ErrorCode;
import com.rpc.common.exception.RpcException;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerManager;
import com.rpc.loadbalance.LoadBalancer;
import com.rpc.registry.ServiceRegistry;

import java.net.InetSocketAddress;
import java.util.List;

public class RpcServiceResolver {
    private final ServiceRegistry serviceRegistry;
    private final LoadBalancer loadBalancer;
    private final CircuitBreakerManager circuitBreakerManager;

    public RpcServiceResolver(ServiceRegistry serviceRegistry,
                              LoadBalancer loadBalancer,
                              CircuitBreakerManager circuitBreakerManager) {
        this.serviceRegistry = serviceRegistry;
        this.loadBalancer = loadBalancer;
        this.circuitBreakerManager = circuitBreakerManager;
    }

    public InetSocketAddress resolve(String serviceName) throws RpcException {
        List<InetSocketAddress> addresses = serviceRegistry.lookup(serviceName);
        if (addresses == null || addresses.isEmpty()) {
            throw new RpcException(ErrorCode.SERVICE_NOT_FOUND, "服务未找到: " + serviceName);
        }
        return loadBalancer.selectWithCircuitBreaker(serviceName, addresses, circuitBreakerManager);
    }
}
