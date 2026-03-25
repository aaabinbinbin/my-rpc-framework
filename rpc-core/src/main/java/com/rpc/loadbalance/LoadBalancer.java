package com.rpc.loadbalance;

import com.rpc.common.exception.dedicated.CircuitBreakerException;
import com.rpc.faulttolerance.CircuitBreaker;
import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerManager;
import com.rpc.spi.SPI;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 负载均衡器接口
 */
@SPI("random")
public interface LoadBalancer {
    /**
     * 从服务列表中选择一个节点
     * @param serviceName 服务名称
     * @param addresses 服务地址列表
     * @return 选中的地址
     */
    InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses);

    /**
     * 获取负载均衡策略名称
     */
    String getName();

    /**
     * 带熔断检查的选择（新增）
     * @param serviceName 服务名称
     * @param addresses 服务地址列表
     * @param circuitBreakerManager 熔断器管理器
     * @return 选中的地址
     */
    default InetSocketAddress selectWithCircuitBreaker(
            String serviceName,
            List<InetSocketAddress> addresses,
            CircuitBreakerManager circuitBreakerManager) throws CircuitBreakerException {

        // 默认实现：过滤掉熔断的实例，然后选择
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }

        // 过滤健康的实例
        List<InetSocketAddress> healthyAddresses = addresses.stream()
                .filter(address -> {
                    CircuitBreaker cb = circuitBreakerManager.getInstanceCircuitBreaker(
                            serviceName, address);
                    return cb.allowRequest();
                })
                .toList();

        if (healthyAddresses.isEmpty()) {
            // 所有实例都熔断了，抛出异常
            throw new CircuitBreakerException(serviceName);
        }

        // 从健康实例中选择
        InetSocketAddress selected = select(serviceName, healthyAddresses);

        // 记录选择（用于后续失败时记录到对应实例的熔断器）
        recordSelection(serviceName, selected, circuitBreakerManager);

        return selected;
    }

    /**
     * 记录选择结果（用于熔断器统计）
     */
    default void recordSelection(String serviceName,
                                 InetSocketAddress address,
                                 CircuitBreakerManager circuitBreakerManager) {
        // 默认空实现，子类可以重写
    }

}
