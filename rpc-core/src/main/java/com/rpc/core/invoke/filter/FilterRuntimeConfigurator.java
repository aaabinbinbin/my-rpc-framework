package com.rpc.core.invoke.filter;

import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.resilience.DegradationPolicy;

/**
 * 过滤器运行时配置器。
 *
 * 过滤器本身通常是无状态的 SPI 扩展，
 * 但有些 filter 运行时还需要读取全局开关、阈值和降级策略等配置。
 * 这些动态参数由当前类统一写入 FilterRuntimeConfig。
 */
public final class FilterRuntimeConfigurator {
    private FilterRuntimeConfigurator() {
    }

    /**
     * 配置 consumer 侧运行时参数。
     *
     * 当前主要作用于 consumer 熔断 / 降级类过滤器。
     */
    public static void configureConsumer(RpcFrameworkConfig frameworkConfig, DegradationPolicy degradationPolicy) {
        FilterRuntimeConfig.configureConsumerDegradation(
                frameworkConfig.isEnableDegradation(),
                frameworkConfig.getDegradationFailureThreshold(),
                degradationPolicy
        );
    }

    /**
     * 配置 provider 侧运行时参数。
     *
     * 当前主要作用于 provider 限流和 provider 降级类过滤器。
     */
    public static void configureProvider(RpcFrameworkConfig frameworkConfig, DegradationPolicy degradationPolicy) {
        FilterRuntimeConfig.configureProviderRateLimit(
                frameworkConfig.isServerRateLimitEnabled(),
                frameworkConfig.getServerRateLimitPermitsPerSecond()
        );
        FilterRuntimeConfig.configureProviderDegradation(
                frameworkConfig.isServerDegradationEnabled(),
                degradationPolicy
        );
    }
}
