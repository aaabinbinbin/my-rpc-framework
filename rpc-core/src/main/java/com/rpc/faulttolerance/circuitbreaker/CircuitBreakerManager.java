package com.rpc.faulttolerance.circuitbreaker;

import com.rpc.faulttolerance.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器管理器
 * 为每个服务维护独立的熔断器
 */
@Slf4j
public class CircuitBreakerManager {
    /** 单例 */
    private static final CircuitBreakerManager INSTANCE = new CircuitBreakerManager();

    /** 服务级熔断器缓存：serviceName -> CircuitBreaker */
    private final ConcurrentHashMap<String, CircuitBreaker> serviceCircuitBreakers
            = new ConcurrentHashMap<>();

    /** 实例级熔断器缓存：serviceName#address -> CircuitBreaker */
    private final ConcurrentHashMap<String, CircuitBreaker> instanceCircuitBreakers
            = new ConcurrentHashMap<>();

    /** 默认配置 */
    private float failureRateThreshold = 50.0f;
    private int minNumberOfCalls = 10;
    private long waitDurationInOpenState = 30000;
    private int permittedNumberOfCallsInHalfOpenState = 5;

    /** 是否启用实例级熔断 */
    private boolean enableInstanceLevelCircuitBreaker = true;

    private CircuitBreakerManager() {}

    public static CircuitBreakerManager getInstance() {
        return INSTANCE;
    }

    /**
     * 获取服务级熔断器
     */
    public CircuitBreaker getServiceCircuitBreaker(String serviceName) {
        return serviceCircuitBreakers.computeIfAbsent(serviceName,
                name -> createCircuitBreaker("service:" + name));
    }

    /**
     * 获取实例级熔断器
     * @param serviceName 服务名称
     * @param address 实例地址
     * @return 实例熔断器
     */
    public CircuitBreaker getInstanceCircuitBreaker(String serviceName,
                                                    InetSocketAddress address) {
        String key = buildInstanceKey(serviceName, address);
        return instanceCircuitBreakers.computeIfAbsent(key,
                k -> createCircuitBreaker("instance:" + key));
    }

    /**
     * 构建实例键
     */
    private String buildInstanceKey(String serviceName, InetSocketAddress address) {
        return serviceName + "#" + address.getHostString() + ":" + address.getPort();
    }

    /**
     * 创建熔断器
     */
    private CircuitBreaker createCircuitBreaker(String name) {
        log.info("创建熔断器：{}", name);
        return new CircuitBreakerImpl(
                name,
                failureRateThreshold,
                minNumberOfCalls,
                waitDurationInOpenState,
                permittedNumberOfCallsInHalfOpenState
        );
    }

    /**
     * 配置全局参数
     */
    public void configure(float failureRateThreshold,
                          int minNumberOfCalls,
                          long waitDurationInOpenState,
                          int permittedNumberOfCallsInHalfOpenState) {
        this.failureRateThreshold = failureRateThreshold;
        this.minNumberOfCalls = minNumberOfCalls;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;

        log.info("熔断器全局配置更新...");
    }

    /**
     * 启用/禁用实例级熔断
     */
    public void setEnableInstanceLevelCircuitBreaker(boolean enable) {
        this.enableInstanceLevelCircuitBreaker = enable;
        log.info("实例级熔断已{}", enable ? "启用" : "禁用");
    }

    /**
     * 重置指定服务的熔断器
     */
    public void resetServiceCircuitBreaker(String serviceName) {
        CircuitBreaker breaker = serviceCircuitBreakers.get(serviceName);
        if (breaker != null) {
            breaker.reset();
        }
    }

    /**
     * 重置指定实例的熔断器
     */
    public void resetInstanceCircuitBreaker(String serviceName, InetSocketAddress address) {
        String key = buildInstanceKey(serviceName, address);
        CircuitBreaker breaker = instanceCircuitBreakers.get(key);
        if (breaker != null) {
            breaker.reset();
        }
    }

    /**
     * 打印所有熔断器状态
     */
    public void printStatus() {
        log.info("========== 服务级熔断器状态 ==========");
        serviceCircuitBreakers.forEach((name, breaker) -> {
            log.info("服务：{}, 状态：{}", name, breaker.getState());
        });

        log.info("========== 实例级熔断器状态 ==========");
        instanceCircuitBreakers.forEach((key, breaker) -> {
            log.info("实例：{}, 状态：{}", key, breaker.getState());
        });
    }
}
