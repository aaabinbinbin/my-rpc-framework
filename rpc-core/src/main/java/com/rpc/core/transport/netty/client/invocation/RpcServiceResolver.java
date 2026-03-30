package com.rpc.core.transport.netty.client.invocation;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.discovery.ServiceDirectory;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.loadbalance.factory.LoadBalancerFactory;

import java.net.InetSocketAddress;
import java.util.List;

public class RpcServiceResolver {
    private final ServiceDirectory serviceDirectory;
    private final LoadBalancer loadBalancer;
    private final CircuitBreakerManager circuitBreakerManager;

    public RpcServiceResolver(ServiceDirectory serviceDirectory,
                              LoadBalancer loadBalancer,
                              CircuitBreakerManager circuitBreakerManager) {
        this.serviceDirectory = serviceDirectory;
        this.loadBalancer = loadBalancer;
        this.circuitBreakerManager = circuitBreakerManager;
    }

    public InetSocketAddress resolve(String serviceName) throws RpcException {
        return resolve(serviceName, null);
    }

    public InetSocketAddress resolve(String serviceName, String loadBalancerName) throws RpcException {
        // resolver 只做三件事：拿地址列表、选负载均衡器、在可用实例里选一个地址。
        List<InetSocketAddress> addresses = serviceDirectory.getSnapshot(serviceName).getAddresses();
        if (addresses == null || addresses.isEmpty()) {
            throw new RpcException(ErrorCode.SERVICE_NOT_FOUND, "Service not found: " + serviceName);
        }
        LoadBalancer selected = loadBalancerName == null || loadBalancerName.isBlank()
                ? loadBalancer
                : LoadBalancerFactory.getLoadBalancer(loadBalancerName);
        // 负载均衡选择时会顺带过滤掉当前已经被实例级熔断器拦住的地址。
        return selected.selectWithCircuitBreaker(serviceName, addresses, circuitBreakerManager);
    }
}

