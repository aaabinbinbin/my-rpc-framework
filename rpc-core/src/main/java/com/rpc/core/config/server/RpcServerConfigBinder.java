package com.rpc.core.config.server;

import com.rpc.core.config.framework.RpcConfigKeys;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.config.source.RpcPropertySource;

/**
 * 服务端配置绑定器。
 *
 * 所处阶段：配置加载后、provider Bootstrap 创建 RpcServer 前。
 * 主要职责：绑定服务端地址、扫描包、Netty 线程数、业务线程池、限流、降级、优雅停机和空闲检测配置。
 *
 * 注意事项：未配置项保留 RpcFrameworkConfig 默认值，保证 provider 最小配置只需要关心端口和注册中心。
 */
public final class RpcServerConfigBinder {
    /**
     * 将 provider 侧配置写入统一框架配置对象。
     *
     * 边界处理：线程池、限流等数值合法性主要在使用端二次兜底，绑定层只负责类型转换和默认值保留。
     */
    public void bind(RpcPropertySource propertySource, RpcFrameworkConfig config) {
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
