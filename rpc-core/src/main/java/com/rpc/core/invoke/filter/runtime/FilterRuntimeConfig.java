package com.rpc.core.invoke.filter.runtime;

import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.resilience.ratelimit.RateLimiterManager;

/**
 * 过滤器运行时共享配置。
 *
 * 所处阶段：FilterRuntimeConfigurator 根据框架配置初始化后，具体过滤器在请求执行期间读取。
 * 主要职责：保存 provider 限流、provider 降级、consumer 降级等运行时状态。
 *
 * 注意事项：字段使用 volatile，保证启动配置或测试重置后其他请求线程可以看到最新值；
 * 复杂状态交给 RateLimiterManager、CircuitBreakerManager 自身保证线程安全。
 */
public final class FilterRuntimeConfig {
    /** provider 侧按服务/方法维度复用的限流器管理器。 */
    private static final RateLimiterManager PROVIDER_RATE_LIMITER = new RateLimiterManager();
    /** provider 限流总开关。 */
    private static volatile boolean providerRateLimitEnabled;
    /** provider 限流默认 QPS，低于 1 时会被兜底为 1。 */
    private static volatile int providerRateLimitPermitsPerSecond = 200;
    /** provider 降级总开关。 */
    private static volatile boolean providerDegradationEnabled;
    /** provider 降级策略，可为空，表示不降级。 */
    private static volatile DegradationPolicy providerDegradationPolicy;
    /** consumer 降级总开关。 */
    private static volatile boolean consumerDegradationEnabled;
    /** consumer 降级策略，可为空，表示不降级。 */
    private static volatile DegradationPolicy consumerDegradationPolicy;

    /** 工具类不允许实例化。 */
    private FilterRuntimeConfig() {
    }

    /**
     * 配置 provider 侧限流。
     *
     * 边界处理：permitsPerSecond 最小为 1，避免限流器内部出现非法窗口容量。
     */
    public static void configureProviderRateLimit(boolean enabled, int permitsPerSecond) {
        providerRateLimitEnabled = enabled;
        providerRateLimitPermitsPerSecond = Math.max(1, permitsPerSecond);
        PROVIDER_RATE_LIMITER.configure(enabled, providerRateLimitPermitsPerSecond);
    }

    /**
     * 尝试获取 provider 侧限流许可。
     *
     * 边界处理：限流关闭时直接放行，避免无意义创建限流器。
     */
    public static boolean tryAcquireProvider(String key) {
        if (!providerRateLimitEnabled) {
            return true;
        }
        return PROVIDER_RATE_LIMITER.tryAcquire(key, providerRateLimitPermitsPerSecond);
    }

    /**
     * 配置 provider 侧降级策略。
     */
    public static void configureProviderDegradation(boolean enabled, DegradationPolicy degradationPolicy) {
        providerDegradationEnabled = enabled;
        providerDegradationPolicy = degradationPolicy;
    }

    /**
     * 判断 provider 侧降级是否启用。
     */
    public static boolean isProviderDegradationEnabled() {
        return providerDegradationEnabled;
    }

    /**
     * 获取 provider 侧降级策略。
     */
    public static DegradationPolicy getProviderDegradationPolicy() {
        return providerDegradationPolicy;
    }

    /**
     * 配置 consumer 侧降级策略。
     */
    public static void configureConsumerDegradation(boolean enabled, DegradationPolicy degradationPolicy) {
        consumerDegradationEnabled = enabled;
        consumerDegradationPolicy = degradationPolicy;
    }

    /**
     * 判断 consumer 侧降级是否启用。
     */
    public static boolean isConsumerDegradationEnabled() {
        return consumerDegradationEnabled;
    }

    /**
     * 获取 consumer 侧降级策略。
     */
    public static DegradationPolicy getConsumerDegradationPolicy() {
        return consumerDegradationPolicy;
    }

    /**
     * 重置 provider 侧运行时配置。
     *
     * 适用场景：测试隔离、服务端 Bootstrap 关闭或重新初始化。
     */
    public static void resetProvider() {
        configureProviderRateLimit(false, 200);
        configureProviderDegradation(false, null);
    }

    /**
     * 重置 consumer 侧运行时配置。
     */
    public static void resetConsumer() {
        configureConsumerDegradation(false, null);
    }

    /**
     * 重置所有过滤器运行时配置。
     */
    public static void resetAll() {
        resetProvider();
        resetConsumer();
    }

    /**
     * 获取全局熔断器管理器。
     *
     * 注意事项：熔断器是 consumer 调用链共享状态，不能每个请求创建新实例。
     */
    public static CircuitBreakerManager getCircuitBreakerManager() {
        return CircuitBreakerManager.getInstance();
    }
}
