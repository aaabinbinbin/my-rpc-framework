package com.rpc.core.config.client;

import com.rpc.core.config.framework.RpcConfigKeys;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.config.method.RpcMethodConfigBinder;
import com.rpc.core.config.source.RpcPropertySource;
import com.rpc.core.invoke.invocation.ClusterStrategy;

/**
 * 客户端配置绑定器。
 *
 * 所处阶段：RpcConfigLoader 读取 rpc.properties 后，把原始字符串配置绑定到 RpcFrameworkConfig。
 * 主要职责：处理 consumer 侧连接、超时、重试、重连、服务发现缓存、限流、降级和熔断配置。
 *
 * 注意事项：未显式配置的字段必须保留 RpcFrameworkConfig 中的默认值，保证用户配置足够精简。
 */
public final class RpcClientConfigBinder {
    /** 方法级配置绑定器，用于解析 rpc.client.methods 指定的细粒度覆盖项。 */
    private final RpcMethodConfigBinder methodConfigBinder = new RpcMethodConfigBinder();

    /**
     * 将客户端配置写入统一框架配置对象。
     *
     * 边界处理：所有 getXxx 调用都传入当前 config 默认值，缺省配置不会覆盖默认值。
     */
    public void bind(RpcPropertySource propertySource, RpcFrameworkConfig config) {
        config.setConnectTimeout(propertySource.getInt(RpcConfigKeys.CLIENT_CONNECT_TIMEOUT, config.getConnectTimeout()));
        config.setReadTimeout(propertySource.getInt(RpcConfigKeys.CLIENT_READ_TIMEOUT, config.getReadTimeout()));
        config.setHeartbeatInterval(propertySource.getInt(RpcConfigKeys.CLIENT_HEARTBEAT_INTERVAL, config.getHeartbeatInterval()));
        config.setMaxInflightRequestsPerConnection(propertySource.getInt(
                RpcConfigKeys.CLIENT_MAX_INFLIGHT_REQUESTS_PER_CONNECTION,
                config.getMaxInflightRequestsPerConnection()
        ));
        config.setMaxConnectionsPerAddress(propertySource.getInt(
                RpcConfigKeys.CLIENT_MAX_CONNECTIONS_PER_ADDRESS,
                config.getMaxConnectionsPerAddress()
        ));
        config.setMaxTotalConnections(propertySource.getInt(
                RpcConfigKeys.CLIENT_MAX_TOTAL_CONNECTIONS,
                config.getMaxTotalConnections()
        ));
        config.setMaxPendingRequests(propertySource.getInt(
                RpcConfigKeys.CLIENT_MAX_PENDING_REQUESTS,
                config.getMaxPendingRequests()
        ));
        config.setIdleConnectionTtlMillis(propertySource.getLong(
                RpcConfigKeys.CLIENT_IDLE_CONNECTION_TTL,
                config.getIdleConnectionTtlMillis()
        ));
        config.setIdleConnectionEvictIntervalMillis(propertySource.getLong(
                RpcConfigKeys.CLIENT_IDLE_CONNECTION_EVICT_INTERVAL,
                config.getIdleConnectionEvictIntervalMillis()
        ));
        config.setWriterIdleTime(propertySource.getInt(RpcConfigKeys.CLIENT_WRITER_IDLE, config.getWriterIdleTime()));
        config.setReaderIdleTime(propertySource.getInt(RpcConfigKeys.CLIENT_READER_IDLE, config.getReaderIdleTime()));
        config.setRetryTimes(propertySource.getInt(RpcConfigKeys.CLIENT_RETRY_TIMES, config.getRetryTimes()));
        config.setClusterStrategy(ClusterStrategy.from(
                propertySource.get(RpcConfigKeys.CLIENT_CLUSTER, config.getClusterStrategy().name())
        ));
        config.setMethodConfigs(methodConfigBinder.bind(propertySource));

        config.setReconnectEnabled(propertySource.getBoolean(RpcConfigKeys.CLIENT_RECONNECT_ENABLED, config.isReconnectEnabled()));
        config.setReconnectMaxRetryTimes(propertySource.getInt(
                RpcConfigKeys.CLIENT_RECONNECT_MAX_RETRY_TIMES,
                config.getReconnectMaxRetryTimes()
        ));
        config.setReconnectInitialDelaySeconds(propertySource.getInt(
                RpcConfigKeys.CLIENT_RECONNECT_INITIAL_DELAY,
                config.getReconnectInitialDelaySeconds()
        ));
        config.setReconnectMaxDelaySeconds(propertySource.getInt(
                RpcConfigKeys.CLIENT_RECONNECT_MAX_DELAY,
                config.getReconnectMaxDelaySeconds()
        ));
        config.setReconnectJitterEnabled(propertySource.getBoolean(
                RpcConfigKeys.CLIENT_RECONNECT_JITTER_ENABLED,
                config.isReconnectJitterEnabled()
        ));
        config.setReconnectJitterMinSeconds(propertySource.getInt(
                RpcConfigKeys.CLIENT_RECONNECT_JITTER_MIN,
                config.getReconnectJitterMinSeconds()
        ));
        config.setReconnectJitterMaxSeconds(propertySource.getInt(
                RpcConfigKeys.CLIENT_RECONNECT_JITTER_MAX,
                config.getReconnectJitterMaxSeconds()
        ));

        config.setDiscoveryPreheatEnabled(propertySource.getBoolean(
                RpcConfigKeys.CLIENT_DISCOVERY_PREHEAT_ENABLED,
                config.isDiscoveryPreheatEnabled()
        ));
        config.setDiscoveryPreheatServices(propertySource.getList(
                RpcConfigKeys.CLIENT_DISCOVERY_PREHEAT_SERVICES,
                config.getDiscoveryPreheatServices()
        ));
        config.setDiscoveryCacheTtlMillis(propertySource.getLong(
                RpcConfigKeys.CLIENT_DISCOVERY_CACHE_TTL,
                config.getDiscoveryCacheTtlMillis()
        ));
        config.setDiscoveryAllowStaleOnFailure(propertySource.getBoolean(
                RpcConfigKeys.CLIENT_DISCOVERY_ALLOW_STALE,
                config.isDiscoveryAllowStaleOnFailure()
        ));

        config.setConsumerDegradationPolicy(propertySource.get(
                RpcConfigKeys.CLIENT_DEGRADATION_POLICY,
                config.getConsumerDegradationPolicy()
        ));
        config.setConsumerDegradationDefaultValues(
                propertySource.getStringMapByPrefix(RpcConfigKeys.CLIENT_DEGRADATION_DEFAULT_VALUE_PREFIX)
        );
        config.setEnableDegradation(propertySource.getBoolean(
                RpcConfigKeys.CLIENT_ENABLE_DEGRADATION,
                config.isEnableDegradation()
        ));
        config.setRateLimitEnabled(propertySource.getBoolean(
                RpcConfigKeys.CLIENT_RATE_LIMIT_ENABLED,
                config.isRateLimitEnabled()
        ));
        config.setRateLimitPermitsPerSecond(propertySource.getInt(
                RpcConfigKeys.CLIENT_RATE_LIMIT_PERMITS_PER_SECOND,
                config.getRateLimitPermitsPerSecond()
        ));

        config.setCircuitBreakerFailureRateThreshold(Float.parseFloat(propertySource.get(
                RpcConfigKeys.CLIENT_CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD,
                String.valueOf(config.getCircuitBreakerFailureRateThreshold())
        )));
        config.setCircuitBreakerMinNumberOfCalls(propertySource.getInt(
                RpcConfigKeys.CLIENT_CIRCUIT_BREAKER_MIN_NUMBER_OF_CALLS,
                config.getCircuitBreakerMinNumberOfCalls()
        ));
        config.setCircuitBreakerWaitDurationInOpenStateMillis(propertySource.getLong(
                RpcConfigKeys.CLIENT_CIRCUIT_BREAKER_WAIT_DURATION_OPEN_MILLIS,
                config.getCircuitBreakerWaitDurationInOpenStateMillis()
        ));
        config.setCircuitBreakerPermittedHalfOpenCalls(propertySource.getInt(
                RpcConfigKeys.CLIENT_CIRCUIT_BREAKER_HALF_OPEN_CALLS,
                config.getCircuitBreakerPermittedHalfOpenCalls()
        ));
    }
}
