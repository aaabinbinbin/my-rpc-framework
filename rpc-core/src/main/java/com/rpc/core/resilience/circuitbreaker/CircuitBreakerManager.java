package com.rpc.core.resilience.circuitbreaker;

import com.rpc.core.resilience.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器管理中心。
 *
 * 这个类统一维护两类熔断器：
 * 1. 服务级熔断器：保护整个逻辑服务。
 * 2. 实例级熔断器：保护某个具体 provider 节点。
 *
 * 这样做的原因是，服务整体不可用和单个节点异常是两种不同问题，
 * 需要分别控制隔离粒度。
 */
@Slf4j
public class CircuitBreakerManager {
    private static final CircuitBreakerManager INSTANCE = new CircuitBreakerManager();

    /** 服务级熔断器，key 通常是 serviceName。 */
    private final ConcurrentHashMap<String, CircuitBreaker> serviceCircuitBreakers = new ConcurrentHashMap<>();
    /** 实例级熔断器，key 通常是 serviceName + host:port。 */
    private final ConcurrentHashMap<String, CircuitBreaker> instanceCircuitBreakers = new ConcurrentHashMap<>();

    /** 失败率阈值，达到后触发熔断。 */
    private float failureRateThreshold = 50.0f;
    /** 最小调用次数，小于该值时不进行失败率判断。 */
    private int minNumberOfCalls = 10;
    /** OPEN 状态持续时长，到期后可进入 HALF_OPEN。 */
    private long waitDurationInOpenState = 30_000L;
    /** HALF_OPEN 状态下允许通过的探测请求数量。 */
    private int permittedNumberOfCallsInHalfOpenState = 5;
    /** 是否启用实例级熔断。 */
    private boolean enableInstanceLevelCircuitBreaker = true;

    private CircuitBreakerManager() {
    }

    public static CircuitBreakerManager getInstance() {
        return INSTANCE;
    }

    /** 获取或创建某个逻辑服务对应的服务级熔断器。 */
    public CircuitBreaker getServiceCircuitBreaker(String serviceName) {
        // 服务级熔断器保护的是整个服务，而不是单个节点。
        return serviceCircuitBreakers.computeIfAbsent(serviceName,
                name -> createCircuitBreaker("service:" + name));
    }

    /** 获取或创建某个具体实例对应的实例级熔断器。 */
    public CircuitBreaker getInstanceCircuitBreaker(String serviceName, InetSocketAddress address) {
        // 实例级熔断器粒度更细，可以只隔离坏节点，不影响整个服务。
        String key = buildInstanceKey(serviceName, address);
        return instanceCircuitBreakers.computeIfAbsent(key,
                name -> createCircuitBreaker("instance:" + name));
    }

    /** 动态修改熔断参数，影响之后新创建的熔断器。 */
    public void configure(float failureRateThreshold,
                          int minNumberOfCalls,
                          long waitDurationInOpenState,
                          int permittedNumberOfCallsInHalfOpenState) {
        // 重新配置只影响后续新创建的熔断器；已存在对象会保留当前状态。
        this.failureRateThreshold = failureRateThreshold;
        this.minNumberOfCalls = minNumberOfCalls;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        log.info("Circuit breaker manager reconfigured");
    }

    public void setEnableInstanceLevelCircuitBreaker(boolean enable) {
        this.enableInstanceLevelCircuitBreaker = enable;
        log.info("Instance-level circuit breaker {}", enable ? "enabled" : "disabled");
    }

    public boolean isEnableInstanceLevelCircuitBreaker() {
        return enableInstanceLevelCircuitBreaker;
    }

    /** 手动重置某个服务级熔断器。 */
    public void resetServiceCircuitBreaker(String serviceName) {
        CircuitBreaker breaker = serviceCircuitBreakers.get(serviceName);
        if (breaker != null) {
            breaker.reset();
        }
    }

    /** 手动重置某个实例级熔断器。 */
    public void resetInstanceCircuitBreaker(String serviceName, InetSocketAddress address) {
        String key = buildInstanceKey(serviceName, address);
        CircuitBreaker breaker = instanceCircuitBreakers.get(key);
        if (breaker != null) {
            breaker.reset();
        }
    }

    /** 打印当前所有熔断器状态，便于排查线上问题。 */
    public void printStatus() {
        log.info("========== Service circuit breakers ==========");
        serviceCircuitBreakers.forEach((name, breaker) ->
                log.info("service={}, state={}", name, breaker.getState()));

        log.info("========== Instance circuit breakers ==========");
        instanceCircuitBreakers.forEach((name, breaker) ->
                log.info("instance={}, state={}", name, breaker.getState()));
    }

    /** 清空所有熔断器，通常用于测试场景或完全重置运行态。 */
    public void clear() {
        serviceCircuitBreakers.clear();
        instanceCircuitBreakers.clear();
    }

    /** 构造实例级熔断器的唯一 key。 */
    private String buildInstanceKey(String serviceName, InetSocketAddress address) {
        return serviceName + "#" + address.getHostString() + ":" + address.getPort();
    }

    /** 根据当前默认参数创建一个新的熔断器实现。 */
    private CircuitBreaker createCircuitBreaker(String name) {
        log.info("Create circuit breaker: {}", name);
        return new CircuitBreakerImpl(
                name,
                failureRateThreshold,
                minNumberOfCalls,
                waitDurationInOpenState,
                permittedNumberOfCallsInHalfOpenState
        );
    }
}
