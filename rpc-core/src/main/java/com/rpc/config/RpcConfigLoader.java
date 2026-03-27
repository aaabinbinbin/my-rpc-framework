package com.rpc.config;

import com.rpc.registry.RegistryType;
import com.rpc.transport.TransportType;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class RpcConfigLoader {
    private RpcConfigLoader() {
    }

    public static RpcFrameworkConfig load() {
        Properties properties = new Properties();
        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(RpcConfigKeys.FILE_NAME)) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RpcConfigKeys.FILE_NAME, e);
        }

        RpcFrameworkConfig config = new RpcFrameworkConfig();
        config.setTransportType(TransportType.from(get(properties, RpcConfigKeys.TRANSPORT, config.getTransportType().name())));
        config.setSerializer(get(properties, RpcConfigKeys.SERIALIZER, config.getSerializer()));
        config.setLoadBalancer(get(properties, RpcConfigKeys.LOAD_BALANCER, config.getLoadBalancer()));

        config.setRegistryType(RegistryType.from(get(properties, RpcConfigKeys.REGISTRY_TYPE, config.getRegistryType().name())));
        config.setRegistryAddress(get(properties, RpcConfigKeys.REGISTRY_ADDRESS, config.getRegistryAddress()));
        config.setRegistryTimeout(getInt(properties, RpcConfigKeys.REGISTRY_TIMEOUT, config.getRegistryTimeout()));

        config.setServerHost(get(properties, RpcConfigKeys.SERVER_HOST, config.getServerHost()));
        config.setServerPort(getInt(properties, RpcConfigKeys.SERVER_PORT, config.getServerPort()));
        config.setBossThreads(getInt(properties, RpcConfigKeys.SERVER_BOSS_THREADS, config.getBossThreads()));
        config.setWorkerThreads(getInt(properties, RpcConfigKeys.SERVER_WORKER_THREADS, config.getWorkerThreads()));
        config.setShutdownTimeout(getInt(properties, RpcConfigKeys.SERVER_SHUTDOWN_TIMEOUT, config.getShutdownTimeout()));
        config.setServerReaderIdleTime(getInt(properties, RpcConfigKeys.SERVER_READER_IDLE, config.getServerReaderIdleTime()));
        config.setServerWriterIdleTime(getInt(properties, RpcConfigKeys.SERVER_WRITER_IDLE, config.getServerWriterIdleTime()));
        config.setServerAllIdleTime(getInt(properties, RpcConfigKeys.SERVER_ALL_IDLE, config.getServerAllIdleTime()));

        config.setConnectTimeout(getInt(properties, RpcConfigKeys.CLIENT_CONNECT_TIMEOUT, config.getConnectTimeout()));
        config.setReadTimeout(getInt(properties, RpcConfigKeys.CLIENT_READ_TIMEOUT, config.getReadTimeout()));
        config.setHeartbeatInterval(getInt(properties, RpcConfigKeys.CLIENT_HEARTBEAT_INTERVAL, config.getHeartbeatInterval()));
        config.setWriterIdleTime(getInt(properties, RpcConfigKeys.CLIENT_WRITER_IDLE, config.getWriterIdleTime()));
        config.setReaderIdleTime(getInt(properties, RpcConfigKeys.CLIENT_READER_IDLE, config.getReaderIdleTime()));
        config.setRetryTimes(getInt(properties, RpcConfigKeys.CLIENT_RETRY_TIMES, config.getRetryTimes()));
        config.setReconnectMaxRetryTimes(getInt(properties, RpcConfigKeys.CLIENT_RECONNECT_MAX_RETRY_TIMES, config.getReconnectMaxRetryTimes()));
        config.setReconnectInitialDelaySeconds(getInt(properties, RpcConfigKeys.CLIENT_RECONNECT_INITIAL_DELAY, config.getReconnectInitialDelaySeconds()));
        config.setReconnectMaxDelaySeconds(getInt(properties, RpcConfigKeys.CLIENT_RECONNECT_MAX_DELAY, config.getReconnectMaxDelaySeconds()));
        config.setEnableDegradation(getBoolean(properties, RpcConfigKeys.CLIENT_ENABLE_DEGRADATION, config.isEnableDegradation()));
        config.setDegradationFailureThreshold(getInt(properties, RpcConfigKeys.CLIENT_DEGRADATION_FAILURE_THRESHOLD, config.getDegradationFailureThreshold()));
        return config;
    }

    private static String get(Properties properties, String key, String defaultValue) {
        return System.getProperty(key, properties.getProperty(key, defaultValue));
    }

    private static int getInt(Properties properties, String key, int defaultValue) {
        return Integer.parseInt(get(properties, key, String.valueOf(defaultValue)));
    }

    private static boolean getBoolean(Properties properties, String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(properties, key, String.valueOf(defaultValue)));
    }
}
