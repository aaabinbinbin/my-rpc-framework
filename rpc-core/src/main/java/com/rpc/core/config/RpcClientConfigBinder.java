package com.rpc.core.config;

import com.rpc.core.invoke.invocation.ClusterStrategy;

final class RpcClientConfigBinder {
    private final RpcMethodConfigBinder methodConfigBinder = new RpcMethodConfigBinder();

    void bind(RpcPropertySource propertySource, RpcFrameworkConfig config) {
        config.setConnectTimeout(propertySource.getInt(RpcConfigKeys.CLIENT_CONNECT_TIMEOUT, config.getConnectTimeout()));
        config.setReadTimeout(propertySource.getInt(RpcConfigKeys.CLIENT_READ_TIMEOUT, config.getReadTimeout()));
        config.setHeartbeatInterval(propertySource.getInt(RpcConfigKeys.CLIENT_HEARTBEAT_INTERVAL, config.getHeartbeatInterval()));
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
        config.setDegradationFailureThreshold(propertySource.getInt(
                RpcConfigKeys.CLIENT_DEGRADATION_FAILURE_THRESHOLD,
                config.getDegradationFailureThreshold()
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
