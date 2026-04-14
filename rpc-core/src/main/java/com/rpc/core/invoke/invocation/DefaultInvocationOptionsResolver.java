package com.rpc.core.invoke.invocation;

import com.rpc.core.protocol.message.RpcRequest;

import java.util.List;

/**
 * 默认调用选项解析器。
 *
 * 这个类负责把“全局默认配置 + 方法级覆盖配置”合并成一次调用最终生效的 InvocationOptions。
 */
public class DefaultInvocationOptionsResolver implements InvocationOptionsResolver {
    /** 全局默认调用选项。 */
    private final InvocationOptions defaults;
    /** 方法级配置列表。 */
    private final List<MethodConfig> methodConfigs;

    public DefaultInvocationOptionsResolver(InvocationOptions defaults, List<MethodConfig> methodConfigs) {
        this.defaults = defaults;
        this.methodConfigs = methodConfigs == null ? List.of() : List.copyOf(methodConfigs);
    }

    /**
     * 解析某次请求最终生效的调用选项。
     *
     * 当前匹配规则比较直接：按 serviceName + methodName 精确匹配 MethodConfig。
     * 匹配到后，只有显式声明的字段会覆盖默认值，其余字段继续继承 defaults。
     */
    @Override
    public InvocationOptions resolve(RpcRequest request) {
        MethodConfig match = methodConfigs.stream()
                .filter(config -> config.getServiceName().equals(request.getServiceName())
                        && config.getMethodName().equals(request.getMethodName()))
                .findFirst()
                .orElse(null);
        if (match == null) {
            return defaults;
        }
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
