package com.rpc.core.invoke.filter;

import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.resilience.ratelimit.RateLimiterManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class FilterRuntimeConfig {
    // provider 侧限流是按 key 计数的，因此复用一个全局 RateLimiterManager 即可。
    private static final RateLimiterManager PROVIDER_RATE_LIMITER = new RateLimiterManager();
    private static volatile boolean providerRateLimitEnabled;
    private static volatile int providerRateLimitPermitsPerSecond = 200;
    private static volatile boolean providerDegradationEnabled;
    private static volatile DegradationPolicy providerDegradationPolicy;
    private static volatile boolean consumerDegradationEnabled;
    private static volatile int consumerDegradationFailureThreshold = 10;
    private static volatile DegradationPolicy consumerDegradationPolicy;
    private static final ConcurrentHashMap<String, AtomicInteger> CONSUMER_FAILURE_COUNTERS = new ConcurrentHashMap<>();

    private FilterRuntimeConfig() {
    }

    public static void configureProviderRateLimit(boolean enabled, int permitsPerSecond) {
        providerRateLimitEnabled = enabled;
        providerRateLimitPermitsPerSecond = Math.max(1, permitsPerSecond);
        PROVIDER_RATE_LIMITER.configure(enabled, providerRateLimitPermitsPerSecond);
    }

    public static boolean tryAcquireProvider(String key) {
        if (!providerRateLimitEnabled) {
            return true;
        }
        return PROVIDER_RATE_LIMITER.tryAcquire(key, providerRateLimitPermitsPerSecond);
    }

    public static void configureProviderDegradation(boolean enabled, DegradationPolicy degradationPolicy) {
        providerDegradationEnabled = enabled;
        providerDegradationPolicy = degradationPolicy;
    }

    public static boolean isProviderDegradationEnabled() {
        return providerDegradationEnabled;
    }

    public static DegradationPolicy getProviderDegradationPolicy() {
        return providerDegradationPolicy;
    }

    public static void configureConsumerDegradation(boolean enabled,
                                                    int failureThreshold,
                                                    DegradationPolicy degradationPolicy) {
        consumerDegradationEnabled = enabled;
        consumerDegradationFailureThreshold = Math.max(1, failureThreshold);
        consumerDegradationPolicy = degradationPolicy;
        // 重新配置时清空历史失败计数，避免旧状态污染新配置。
        CONSUMER_FAILURE_COUNTERS.clear();
    }

    public static boolean isConsumerDegradationEnabled() {
        return consumerDegradationEnabled;
    }

    public static int getConsumerFailureThreshold() {
        return consumerDegradationFailureThreshold;
    }

    public static DegradationPolicy getConsumerDegradationPolicy() {
        return consumerDegradationPolicy;
    }

    public static int incrementConsumerFailure(String key) {
        return CONSUMER_FAILURE_COUNTERS.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public static int getConsumerFailureCount(String key) {
        return CONSUMER_FAILURE_COUNTERS.computeIfAbsent(key, ignored -> new AtomicInteger()).get();
    }

    public static void resetConsumerFailure(String key) {
        AtomicInteger counter = CONSUMER_FAILURE_COUNTERS.get(key);
        if (counter != null) {
            counter.set(0);
        }
    }

    public static CircuitBreakerManager getCircuitBreakerManager() {
        return CircuitBreakerManager.getInstance();
    }
}

