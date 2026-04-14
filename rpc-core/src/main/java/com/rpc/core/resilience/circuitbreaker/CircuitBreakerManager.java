package com.rpc.core.resilience.circuitbreaker;

import com.rpc.core.resilience.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * consumer 侧熔断器管理器。
 *
 * 所处阶段：ConsumerCircuitBreakerFilter 和实例调用链判断是否允许请求继续执行时。
 * 主要职责：按服务级和实例级维护熔断器实例，并支持运行时重配置熔断阈值。
 *
 * 注意事项：服务级熔断和实例级熔断是两套 key，不是重复记账；前者保护服务整体，后者隔离坏实例。
 */
@Slf4j
public class CircuitBreakerManager {
    /** 默认失败率阈值，超过后打开熔断器。 */
    private static final float DEFAULT_FAILURE_RATE_THRESHOLD = 50.0f;
    /** 默认最小统计调用数，样本不足时不计算失败率。 */
    private static final int DEFAULT_MIN_NUMBER_OF_CALLS = 10;
    /** 默认 OPEN 状态等待时间。 */
    private static final long DEFAULT_WAIT_DURATION_IN_OPEN_STATE = 30_000L;
    /** 默认 HALF_OPEN 状态允许的探测请求数。 */
    private static final int DEFAULT_PERMITTED_HALF_OPEN_CALLS = 5;
    /** 默认启用实例级熔断。 */
    private static final boolean DEFAULT_ENABLE_INSTANCE_LEVEL_BREAKER = true;

    /** JVM 内共享的熔断器管理器单例。 */
    private static final CircuitBreakerManager INSTANCE = new CircuitBreakerManager();
    /** 实例级熔断关闭时返回的空操作熔断器，避免调用方写额外分支。 */
    private static final CircuitBreaker NO_OP_CIRCUIT_BREAKER = new CircuitBreaker() {
        @Override
        public boolean allowRequest() {
            return true;
        }

        @Override
        public void recordSuccess() {
        }

        @Override
        public void recordFailure() {
        }

        @Override
        public com.rpc.core.resilience.CircuitBreakerState getState() {
            return com.rpc.core.resilience.CircuitBreakerState.CLOSED;
        }

        @Override
        public void reset() {
        }
    };

    /** 服务级熔断器映射，key 通常是 serviceName 或 serviceName#methodName。 */
    private final ConcurrentHashMap<String, CircuitBreaker> serviceCircuitBreakers = new ConcurrentHashMap<>();
    /** 实例级熔断器映射，key 为 serviceName#host:port。 */
    private final ConcurrentHashMap<String, CircuitBreaker> instanceCircuitBreakers = new ConcurrentHashMap<>();

    /** 当前生效的失败率阈值。 */
    private volatile float failureRateThreshold = DEFAULT_FAILURE_RATE_THRESHOLD;
    /** 当前生效的最小统计调用数。 */
    private volatile int minNumberOfCalls = DEFAULT_MIN_NUMBER_OF_CALLS;
    /** 当前生效的 OPEN 状态等待时间。 */
    private volatile long waitDurationInOpenState = DEFAULT_WAIT_DURATION_IN_OPEN_STATE;
    /** 当前生效的 HALF_OPEN 探测请求数。 */
    private volatile int permittedNumberOfCallsInHalfOpenState = DEFAULT_PERMITTED_HALF_OPEN_CALLS;
    /** 实例级熔断开关。 */
    private volatile boolean enableInstanceLevelCircuitBreaker = DEFAULT_ENABLE_INSTANCE_LEVEL_BREAKER;

    /** 单例类不允许外部实例化。 */
    private CircuitBreakerManager() {
    }

    /**
     * 获取熔断器管理器单例。
     */
    public static CircuitBreakerManager getInstance() {
        return INSTANCE;
    }

    /**
     * 获取服务级熔断器。
     *
     * 边界处理：不存在时按当前配置懒创建。
     */
    public CircuitBreaker getServiceCircuitBreaker(String serviceName) {
        return serviceCircuitBreakers.computeIfAbsent(serviceName, name -> createCircuitBreaker("service:" + name));
    }

    /**
     * 获取实例级熔断器。
     *
     * 边界处理：实例级熔断关闭时返回 NO_OP_CIRCUIT_BREAKER，调用方仍可统一执行 allow/record。
     */
    public CircuitBreaker getInstanceCircuitBreaker(String serviceName, InetSocketAddress address) {
        if (!enableInstanceLevelCircuitBreaker) {
            return NO_OP_CIRCUIT_BREAKER;
        }
        String key = buildInstanceKey(serviceName, address);
        return instanceCircuitBreakers.computeIfAbsent(key, name -> createCircuitBreaker("instance:" + name));
    }

    /**
     * 重新配置后续新建熔断器的参数。
     *
     * 注意事项：已有熔断器不会被强制重建，避免运行中清空统计窗口造成判断抖动；需要重建时可调用 clear/resetAll。
     */
    public synchronized void configure(float failureRateThreshold,
                                       int minNumberOfCalls,
                                       long waitDurationInOpenState,
                                       int permittedNumberOfCallsInHalfOpenState) {
        this.failureRateThreshold = failureRateThreshold;
        this.minNumberOfCalls = minNumberOfCalls;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        log.info("Circuit breaker manager reconfigured");
    }

    /**
     * 设置实例级熔断开关。
     */
    public void setEnableInstanceLevelCircuitBreaker(boolean enable) {
        this.enableInstanceLevelCircuitBreaker = enable;
        log.info("Instance-level circuit breaker {}", enable ? "enabled" : "disabled");
    }

    /**
     * 判断实例级熔断是否启用。
     */
    public boolean isEnableInstanceLevelCircuitBreaker() {
        return enableInstanceLevelCircuitBreaker;
    }

    /**
     * 重置指定服务级熔断器。
     */
    public void resetServiceCircuitBreaker(String serviceName) {
        CircuitBreaker breaker = serviceCircuitBreakers.get(serviceName);
        if (breaker != null) {
            breaker.reset();
        }
    }

    /**
     * 重置指定实例级熔断器。
     */
    public void resetInstanceCircuitBreaker(String serviceName, InetSocketAddress address) {
        String key = buildInstanceKey(serviceName, address);
        CircuitBreaker breaker = instanceCircuitBreakers.get(key);
        if (breaker != null) {
            breaker.reset();
        }
    }

    /**
     * 打印当前熔断器状态，用于调试和压测观察。
     */
    public void printStatus() {
        log.info("========== Service circuit breakers ==========");
        serviceCircuitBreakers.forEach((name, breaker) -> log.info("service={}, state={}", name, breaker.getState()));

        log.info("========== Instance circuit breakers ==========");
        instanceCircuitBreakers.forEach((name, breaker) -> log.info("instance={}, state={}", name, breaker.getState()));
    }

    /**
     * 清空全部熔断器实例。
     */
    public synchronized void clear() {
        serviceCircuitBreakers.clear();
        instanceCircuitBreakers.clear();
    }

    /**
     * 返回服务级熔断器数量，主要用于测试或观测。
     */
    public int serviceBreakerCount() {
        return serviceCircuitBreakers.size();
    }

    /**
     * 返回实例级熔断器数量，主要用于测试或观测。
     */
    public int instanceBreakerCount() {
        return instanceCircuitBreakers.size();
    }

    /**
     * 清空熔断器并恢复默认配置。
     *
     * 适用场景：测试隔离、压测新轮次初始化。
     */
    public synchronized void resetAll() {
        clear();
        failureRateThreshold = DEFAULT_FAILURE_RATE_THRESHOLD;
        minNumberOfCalls = DEFAULT_MIN_NUMBER_OF_CALLS;
        waitDurationInOpenState = DEFAULT_WAIT_DURATION_IN_OPEN_STATE;
        permittedNumberOfCallsInHalfOpenState = DEFAULT_PERMITTED_HALF_OPEN_CALLS;
        enableInstanceLevelCircuitBreaker = DEFAULT_ENABLE_INSTANCE_LEVEL_BREAKER;
    }

    /**
     * 构造实例级熔断 key。
     */
    private String buildInstanceKey(String serviceName, InetSocketAddress address) {
        return serviceName + "#" + address.getHostString() + ":" + address.getPort();
    }

    /**
     * 使用当前配置创建新的熔断器。
     */
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
