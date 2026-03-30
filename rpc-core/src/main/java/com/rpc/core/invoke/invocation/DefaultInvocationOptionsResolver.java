package com.rpc.core.invoke.invocation;

import com.rpc.core.protocol.RpcRequest;

import java.util.List;

public class DefaultInvocationOptionsResolver implements InvocationOptionsResolver {
    private final InvocationOptions defaults;
    private final List<MethodConfig> methodConfigs;

    public DefaultInvocationOptionsResolver(InvocationOptions defaults, List<MethodConfig> methodConfigs) {
        this.defaults = defaults;
        this.methodConfigs = methodConfigs == null ? List.of() : List.copyOf(methodConfigs);
    }

    @Override
    public InvocationOptions resolve(RpcRequest request) {
        // 当前解析规则很直接：serviceName + methodName 精确匹配。
        MethodConfig match = methodConfigs.stream()
                .filter(config -> config.getServiceName().equals(request.getServiceName())
                        && config.getMethodName().equals(request.getMethodName()))
                .findFirst()
                .orElse(null);
        if (match == null) {
            return defaults;
        }
        // 方法级配置只覆盖显式声明的字段，其余字段继续继承全局默认值。
        return InvocationOptions.builder()
                .retryTimes(match.getRetryTimes() == null ? defaults.getRetryTimes() : match.getRetryTimes())
                .clusterStrategy(match.getClusterStrategy() == null ? defaults.getClusterStrategy() : match.getClusterStrategy())
                .readTimeout(match.getReadTimeout() == null ? defaults.getReadTimeout() : match.getReadTimeout())
                .serializerName(match.getSerializerName() == null ? defaults.getSerializerName() : match.getSerializerName())
                .loadBalancerName(match.getLoadBalancerName() == null ? defaults.getLoadBalancerName() : match.getLoadBalancerName())
                .rateLimitEnabled(match.getRateLimitEnabled() == null ? defaults.isRateLimitEnabled() : match.getRateLimitEnabled())
                .rateLimitPermitsPerSecond(match.getRateLimitPermitsPerSecond() == null
                        ? defaults.getRateLimitPermitsPerSecond()
                        : match.getRateLimitPermitsPerSecond())
                .circuitBreakerScope(match.getCircuitBreakerScope() == null
                        ? defaults.getCircuitBreakerScope()
                        : match.getCircuitBreakerScope())
                .build();
    }
}

