package com.rpc.core.extension.loadbalance;

import com.rpc.core.common.exception.dedicated.CircuitBreakerException;
import com.rpc.core.extension.spi.SPI;
import com.rpc.core.resilience.CircuitBreaker;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 负载均衡 SPI（可插拔扩展点），用于从候选地址列表中选出一个提供者地址。
 * 这个抽象只负责决定“下一次调用打到哪个地址”。
 * 重试、熔断和请求发送仍由上层处理。
 */
@SPI("random")
public interface LoadBalancer {
    InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses);

    String getName();

    default InetSocketAddress selectWithCircuitBreaker(String serviceName,
                                                       List<InetSocketAddress> addresses,
                                                       CircuitBreakerManager circuitBreakerManager)
            throws CircuitBreakerException {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }

        // 实例级熔断会先过滤掉暂时不健康的地址，
        // 然后再交给真正的负载均衡策略做选择。
        List<InetSocketAddress> healthyAddresses = addresses.stream()
                .filter(address -> {
                    CircuitBreaker breaker =
                            circuitBreakerManager.getInstanceCircuitBreaker(serviceName, address);
                    return breaker.allowRequest();
                })
                .toList();

        if (healthyAddresses.isEmpty()) {
            throw new CircuitBreakerException(serviceName);
        }

        InetSocketAddress selected = select(serviceName, healthyAddresses);
        recordSelection(serviceName, selected, circuitBreakerManager);
        return selected;
    }

    default void recordSelection(String serviceName,
                                 InetSocketAddress address,
                                 CircuitBreakerManager circuitBreakerManager) {
        // 可选扩展点，供需要记录选择结果的实现使用。
    }
}
