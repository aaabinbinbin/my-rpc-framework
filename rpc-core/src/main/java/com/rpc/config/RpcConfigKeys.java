package com.rpc.config;

public final class RpcConfigKeys {
    public static final String FILE_NAME = "rpc.properties";

    public static final String TRANSPORT = "rpc.transport";
    public static final String SERIALIZER = "rpc.serializer";
    public static final String LOAD_BALANCER = "rpc.loadbalancer";

    public static final String REGISTRY_TYPE = "rpc.registry.type";
    public static final String REGISTRY_ADDRESS = "rpc.registry.address";
    public static final String REGISTRY_TIMEOUT = "rpc.registry.timeout";

    public static final String SERVER_HOST = "rpc.server.host";
    public static final String SERVER_PORT = "rpc.server.port";
    public static final String SERVER_BOSS_THREADS = "rpc.server.bossThreads";
    public static final String SERVER_WORKER_THREADS = "rpc.server.workerThreads";
    public static final String SERVER_SHUTDOWN_TIMEOUT = "rpc.server.shutdownTimeout";
    public static final String SERVER_READER_IDLE = "rpc.server.readerIdleTime";
    public static final String SERVER_WRITER_IDLE = "rpc.server.writerIdleTime";
    public static final String SERVER_ALL_IDLE = "rpc.server.allIdleTime";

    public static final String CLIENT_CONNECT_TIMEOUT = "rpc.client.connectTimeout";
    public static final String CLIENT_READ_TIMEOUT = "rpc.client.readTimeout";
    public static final String CLIENT_HEARTBEAT_INTERVAL = "rpc.client.heartbeatInterval";
    public static final String CLIENT_WRITER_IDLE = "rpc.client.writerIdleTime";
    public static final String CLIENT_READER_IDLE = "rpc.client.readerIdleTime";
    public static final String CLIENT_RETRY_TIMES = "rpc.client.retryTimes";
    public static final String CLIENT_RECONNECT_MAX_RETRY_TIMES = "rpc.client.reconnect.maxRetryTimes";
    public static final String CLIENT_RECONNECT_INITIAL_DELAY = "rpc.client.reconnect.initialDelaySeconds";
    public static final String CLIENT_RECONNECT_MAX_DELAY = "rpc.client.reconnect.maxDelaySeconds";
    public static final String CLIENT_ENABLE_DEGRADATION = "rpc.client.enableDegradation";
    public static final String CLIENT_DEGRADATION_FAILURE_THRESHOLD = "rpc.client.degradationFailureThreshold";

    private RpcConfigKeys() {
    }
}
