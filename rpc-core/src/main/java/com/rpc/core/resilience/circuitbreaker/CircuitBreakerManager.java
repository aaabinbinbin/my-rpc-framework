package com.rpc.core.resilience.circuitbreaker;

import com.rpc.core.resilience.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一管理服务级和实例级熔断器的注册中心。
 */
@Slf4j
public class CircuitBreakerManager {
    private static final CircuitBreakerManager INSTANCE = new CircuitBreakerManager();

    private final ConcurrentHashMap<String, CircuitBreaker> serviceCircuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CircuitBreaker> instanceCircuitBreakers = new ConcurrentHashMap<>();

    private float failureRateThreshold = 50.0f;
    private int minNumberOfCalls = 10;
    private long waitDurationInOpenState = 30_000L;
    private int permittedNumberOfCallsInHalfOpenState = 5;
    private boolean enableInstanceLevelCircuitBreaker = true;

    private CircuitBreakerManager() {
    }

    public static CircuitBreakerManager getInstance() {
        return INSTANCE;
    }

    public CircuitBreaker getServiceCircuitBreaker(String serviceName) {
    // 服务级熔断器保护的是整个逻辑服务。
        return serviceCircuitBreakers.computeIfAbsent(serviceName,
                name -> createCircuitBreaker("service:" + name));
    }

    public CircuitBreaker getInstanceCircuitBreaker(String serviceName, InetSocketAddress address) {
    // 实例级熔断器粒度更细，
    // 可以只隔离少量坏节点，而不是把整个服务一起熔断。
        String key = buildInstanceKey(serviceName, address);
        return instanceCircuitBreakers.computeIfAbsent(key,
                name -> createCircuitBreaker("instance:" + name));
    }

    public void configure(float failureRateThreshold,
                          int minNumberOfCalls,
                          long waitDurationInOpenState,
                          int permittedNumberOfCallsInHalfOpenState) {
    // 重新配置只会影响此后新创建的熔断器；
    // 已存在的对象继续保留当前内存状态。
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

    public void resetServiceCircuitBreaker(String serviceName) {
        CircuitBreaker breaker = serviceCircuitBreakers.get(serviceName);
        if (breaker != null) {
            breaker.reset();
        }
    }

    public void resetInstanceCircuitBreaker(String serviceName, InetSocketAddress address) {
        String key = buildInstanceKey(serviceName, address);
        CircuitBreaker breaker = instanceCircuitBreakers.get(key);
        if (breaker != null) {
            breaker.reset();
        }
    }

    public void printStatus() {
        log.info("========== Service circuit breakers ==========");
        serviceCircuitBreakers.forEach((name, breaker) ->
                log.info("service={}, state={}", name, breaker.getState()));

        log.info("========== Instance circuit breakers ==========");
        instanceCircuitBreakers.forEach((name, breaker) ->
                log.info("instance={}, state={}", name, breaker.getState()));
    }

    public void clear() {
        serviceCircuitBreakers.clear();
        instanceCircuitBreakers.clear();
    }

    private String buildInstanceKey(String serviceName, InetSocketAddress address) {
        return serviceName + "#" + address.getHostString() + ":" + address.getPort();
    }

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
