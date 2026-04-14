package com.rpc.core.config.method;

import com.rpc.core.config.framework.RpcConfigKeys;
import com.rpc.core.config.source.RpcPropertySource;
import com.rpc.core.invoke.invocation.CircuitBreakerScope;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.MethodConfig;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 方法级客户端配置绑定器。
 *
 * 所处阶段：客户端配置绑定时，解析 rpc.client.methods 指定的方法别名列表。
 * 主要职责：把某个服务方法的重试、集群策略、超时、序列化、负载均衡、限流和熔断粒度覆盖绑定成 MethodConfig。
 *
 * 注意事项：方法级配置只写入非空覆盖项，最终如何与全局默认值合并由 InvocationOptionsResolver 处理。
 */
public final class RpcMethodConfigBinder {
    /**
     * 解析所有方法级配置。
     *
     * 边界处理：未配置 rpc.client.methods 时返回空列表；缺少 service 或 method 的别名会被过滤掉，避免生成不可用规则。
     */
    public List<MethodConfig> bind(RpcPropertySource propertySource) {
        List<String> aliases = propertySource.getList(RpcConfigKeys.CLIENT_METHODS, List.of());
        if (aliases.isEmpty()) {
            return List.of();
        }

        return aliases.stream()
                .map(alias -> bindMethodConfig(propertySource, alias))
                .filter(config -> config.getServiceName() != null && config.getMethodName() != null)
                .collect(Collectors.toList());
    }

    /**
     * 绑定单个方法别名下的配置。
     *
     * 配置格式：rpc.client.method.{alias}.service / method / retryTimes 等。
     */
    private MethodConfig bindMethodConfig(RpcPropertySource propertySource, String alias) {
        String prefix = RpcConfigKeys.CLIENT_METHOD_PREFIX + alias + ".";
        return MethodConfig.builder()
                .serviceName(propertySource.get(prefix + "service", null))
                .methodName(propertySource.get(prefix + "method", null))
                .retryTimes(propertySource.getOptionalInt(prefix + "retryTimes"))
                .clusterStrategy(getOptionalCluster(propertySource, prefix + "cluster"))
                .readTimeout(propertySource.getOptionalInt(prefix + "readTimeout"))
                .serializerName(propertySource.get(prefix + "serializer", null))
                .loadBalancerName(propertySource.get(prefix + "loadBalancer", null))
                .rateLimitEnabled(propertySource.getOptionalBoolean(prefix + "rateLimitEnabled"))
                .rateLimitPermitsPerSecond(propertySource.getOptionalInt(prefix + "rateLimitPermitsPerSecond"))
                .circuitBreakerScope(getOptionalCircuitBreakerScope(propertySource, prefix + "circuitBreakerScope"))
                .build();
    }

    /**
     * 解析可选集群策略。
     *
     * 边界处理：空配置返回 null，表示不覆盖全局策略。
     */
    private ClusterStrategy getOptionalCluster(RpcPropertySource propertySource, String key) {
        String raw = propertySource.get(key, null);
        return raw == null || raw.isBlank() ? null : ClusterStrategy.from(raw);
    }

    /**
     * 解析可选熔断统计粒度。
     *
     * 边界处理：空配置返回 null，表示沿用全局默认粒度。
     */
    private CircuitBreakerScope getOptionalCircuitBreakerScope(RpcPropertySource propertySource, String key) {
        String raw = propertySource.get(key, null);
        return raw == null || raw.isBlank() ? null : CircuitBreakerScope.from(raw);
    }
}
