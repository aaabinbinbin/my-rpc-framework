package com.rpc.core.config;

final class RpcServerConfigBinder {
    void bind(RpcPropertySource propertySource, RpcFrameworkConfig config) {
        config.setServerHost(propertySource.get(RpcConfigKeys.SERVER_HOST, config.getServerHost()));
        config.setServerPort(propertySource.getInt(RpcConfigKeys.SERVER_PORT, config.getServerPort()));
        config.setServerScanPackages(propertySource.getList(RpcConfigKeys.SERVER_SCAN_PACKAGES, config.getServerScanPackages()));
        config.setServerAutoRegisterAnnotatedServices(propertySource.getBoolean(
                RpcConfigKeys.SERVER_AUTO_REGISTER_ANNOTATED_SERVICES,
                config.isServerAutoRegisterAnnotatedServices()
        ));
        config.setBossThreads(propertySource.getInt(RpcConfigKeys.SERVER_BOSS_THREADS, config.getBossThreads()));
        config.setWorkerThreads(propertySource.getInt(RpcConfigKeys.SERVER_WORKER_THREADS, config.getWorkerThreads()));
        config.setBizCoreThreads(propertySource.getInt(RpcConfigKeys.SERVER_BIZ_CORE_THREADS, config.getBizCoreThreads()));
        config.setBizMaxThreads(propertySource.getInt(RpcConfigKeys.SERVER_BIZ_MAX_THREADS, config.getBizMaxThreads()));
        config.setBizQueueCapacity(propertySource.getInt(RpcConfigKeys.SERVER_BIZ_QUEUE_CAPACITY, config.getBizQueueCapacity()));
        config.setServerRateLimitEnabled(propertySource.getBoolean(
                RpcConfigKeys.SERVER_RATE_LIMIT_ENABLED,
                config.isServerRateLimitEnabled()
        ));
        config.setServerRateLimitPermitsPerSecond(propertySource.getInt(
                RpcConfigKeys.SERVER_RATE_LIMIT_PERMITS_PER_SECOND,
                config.getServerRateLimitPermitsPerSecond()
        ));
        config.setServerDegradationEnabled(propertySource.getBoolean(
                RpcConfigKeys.SERVER_DEGRADATION_ENABLED,
                config.isServerDegradationEnabled()
        ));
        config.setServerDegradationPolicy(propertySource.get(
                RpcConfigKeys.SERVER_DEGRADATION_POLICY,
                config.getServerDegradationPolicy()
        ));
        config.setServerDegradationDefaultValues(
                propertySource.getStringMapByPrefix(RpcConfigKeys.SERVER_DEGRADATION_DEFAULT_VALUE_PREFIX)
        );
        config.setShutdownTimeout(propertySource.getInt(RpcConfigKeys.SERVER_SHUTDOWN_TIMEOUT, config.getShutdownTimeout()));
        config.setServerReaderIdleTime(propertySource.getInt(RpcConfigKeys.SERVER_READER_IDLE, config.getServerReaderIdleTime()));
        config.setServerWriterIdleTime(propertySource.getInt(RpcConfigKeys.SERVER_WRITER_IDLE, config.getServerWriterIdleTime()));
        config.setServerAllIdleTime(propertySource.getInt(RpcConfigKeys.SERVER_ALL_IDLE, config.getServerAllIdleTime()));
    }
}
