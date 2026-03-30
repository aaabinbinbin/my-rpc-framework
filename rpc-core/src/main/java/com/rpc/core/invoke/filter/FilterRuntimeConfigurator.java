package com.rpc.core.invoke.filter;

import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.resilience.DegradationPolicy;

public final class FilterRuntimeConfigurator {
    private FilterRuntimeConfigurator() {
    }

    public static void configureConsumer(RpcFrameworkConfig frameworkConfig, DegradationPolicy degradationPolicy) {
        // consumer 运行时配置目前主要服务于熔断/降级类 filter。
        FilterRuntimeConfig.configureConsumerDegradation(
                frameworkConfig.isEnableDegradation(),
                frameworkConfig.getDegradationFailureThreshold(),
                degradationPolicy
        );
    }

    public static void configureProvider(RpcFrameworkConfig frameworkConfig, DegradationPolicy degradationPolicy) {
        // provider 运行时配置目前主要服务于限流和降级类 filter。
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

