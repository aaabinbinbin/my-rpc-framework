package com.rpc.core.extension.loadbalance;

import com.rpc.core.common.exception.dedicated.CircuitBreakerException;
import com.rpc.core.extension.spi.SPI;
import com.rpc.core.resilience.CircuitBreaker;
import com.rpc.core.resilience.CircuitBreakerState;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;

import java.util.ArrayList;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * 负载均衡 SPI，用于从候选地址中选择一个 provider。
 *
 * 所处阶段：consumer 已经拿到某个服务的实例快照，准备选择具体 provider 地址。
 * 设计要点：
 * - select 只负责基础选择，不应该直接发送网络请求。
 * - selectWithCircuitBreaker 在选择前后结合实例级熔断，避免把流量打到 OPEN 实例。
 * - recordSelection / releaseSelection 给 least-connections 等有状态算法提供生命周期钩子。
 */
@SPI("random")
public interface LoadBalancer {
    /** 从候选地址中选择一个 provider；实现类只处理算法本身。 */
    InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses);

    /** 扩展名，用于 SPI 按名称加载具体负载均衡器。 */
    String getName();

    /**
     * 带实例级熔断判断的选择入口。
     *
     * 处理顺序：
     * 1. 过滤掉 OPEN 状态实例。
     * 2. 调用具体负载均衡算法选择候选实例。
     * 3. 通过 allowRequest 控制 HALF_OPEN 探测并发。
     * 4. 只有真正允许调用时才 recordSelection，避免计数泄漏。
     */
    default InetSocketAddress selectWithCircuitBreaker(String serviceName,
                                                       List<InetSocketAddress> addresses,
                                                       CircuitBreakerManager circuitBreakerManager)
            throws CircuitBreakerException {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }

        List<InetSocketAddress> remaining = new ArrayList<>(addresses);
        boolean probeExhausted = false;
        while (!remaining.isEmpty()) {
            List<InetSocketAddress> candidateAddresses = remaining.stream()
                    .filter(address -> {
                        CircuitBreaker breaker =
                                circuitBreakerManager.getInstanceCircuitBreaker(serviceName, address);
                        return breaker.getState() != CircuitBreakerState.OPEN;
                    })
                    .toList();

            if (candidateAddresses.isEmpty()) {
                throw new CircuitBreakerException(
                        serviceName,
                        probeExhausted
                                ? CircuitBreakerException.Reason.HALF_OPEN_PROBE_EXHAUSTED
                                : CircuitBreakerException.Reason.ALL_INSTANCES_OPEN
                );
            }

            InetSocketAddress selected = select(serviceName, candidateAddresses);
            if (selected == null) {
                return null;
            }

            CircuitBreaker selectedBreaker =
                    circuitBreakerManager.getInstanceCircuitBreaker(serviceName, selected);
            if (selectedBreaker.allowRequest()) {
                recordSelection(serviceName, selected, circuitBreakerManager);
                return selected;
            }

            probeExhausted = true;
            remaining.remove(selected);
        }

        throw new CircuitBreakerException(
                serviceName,
                probeExhausted
                        ? CircuitBreakerException.Reason.HALF_OPEN_PROBE_EXHAUSTED
                        : CircuitBreakerException.Reason.ALL_INSTANCES_OPEN
        );
    }

    /** 选择被熔断允许后调用；默认无状态算法不需要处理。 */
    default void recordSelection(String serviceName,
                                 InetSocketAddress address,
                                 CircuitBreakerManager circuitBreakerManager) {
    }

    /** 一次调用结束后释放选择状态；默认无状态算法不需要处理。 */
    default void releaseSelection(String serviceName, InetSocketAddress address) {
    }
}
