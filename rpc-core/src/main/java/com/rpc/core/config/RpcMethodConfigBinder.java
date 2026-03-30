package com.rpc.core.config;

import com.rpc.core.invoke.invocation.CircuitBreakerScope;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.MethodConfig;

import java.util.List;
import java.util.stream.Collectors;

final class RpcMethodConfigBinder {
    List<MethodConfig> bind(RpcPropertySource propertySource) {
        List<String> aliases = propertySource.getList(RpcConfigKeys.CLIENT_METHODS, List.of());
        if (aliases.isEmpty()) {
            return List.of();
        }

        return aliases.stream()
                .map(alias -> bindMethodConfig(propertySource, alias))
                .filter(config -> config.getServiceName() != null && config.getMethodName() != null)
                .collect(Collectors.toList());
    }

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

    private ClusterStrategy getOptionalCluster(RpcPropertySource propertySource, String key) {
        String raw = propertySource.get(key, null);
        return raw == null || raw.isBlank() ? null : ClusterStrategy.from(raw);
    }

    private CircuitBreakerScope getOptionalCircuitBreakerScope(RpcPropertySource propertySource, String key) {
        String raw = propertySource.get(key, null);
        return raw == null || raw.isBlank() ? null : CircuitBreakerScope.from(raw);
    }
}
