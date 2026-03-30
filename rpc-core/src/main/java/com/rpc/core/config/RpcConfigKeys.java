package com.rpc.core.config;

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
    public static final String SERVER_SCAN_PACKAGES = "rpc.server.scanPackages";
    public static final String SERVER_AUTO_REGISTER_ANNOTATED_SERVICES = "rpc.server.autoRegisterAnnotatedServices";
    public static final String SERVER_BOSS_THREADS = "rpc.server.bossThreads";
    public static final String SERVER_WORKER_THREADS = "rpc.server.workerThreads";
    public static final String SERVER_BIZ_CORE_THREADS = "rpc.server.biz.coreThreads";
    public static final String SERVER_BIZ_MAX_THREADS = "rpc.server.biz.maxThreads";
    public static final String SERVER_BIZ_QUEUE_CAPACITY = "rpc.server.biz.queueCapacity";
    public static final String SERVER_RATE_LIMIT_ENABLED = "rpc.server.rateLimit.enabled";
    public static final String SERVER_RATE_LIMIT_PERMITS_PER_SECOND = "rpc.server.rateLimit.permitsPerSecond";
    public static final String SERVER_DEGRADATION_ENABLED = "rpc.server.degradation.enabled";
    public static final String SERVER_DEGRADATION_POLICY = "rpc.server.degradation.policy";
    public static final String SERVER_DEGRADATION_DEFAULT_VALUE_PREFIX = "rpc.server.degradation.defaultValue.";
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
    public static final String CLIENT_CLUSTER = "rpc.client.cluster";
    public static final String CLIENT_METHODS = "rpc.client.methods";
    public static final String CLIENT_METHOD_PREFIX = "rpc.client.method.";
    public static final String CLIENT_RECONNECT_MAX_RETRY_TIMES = "rpc.client.reconnect.maxRetryTimes";
    public static final String CLIENT_RECONNECT_INITIAL_DELAY = "rpc.client.reconnect.initialDelaySeconds";
    public static final String CLIENT_RECONNECT_MAX_DELAY = "rpc.client.reconnect.maxDelaySeconds";
    public static final String CLIENT_RECONNECT_ENABLED = "rpc.client.reconnect.enabled";
    public static final String CLIENT_RECONNECT_JITTER_ENABLED = "rpc.client.reconnect.jitter.enabled";
    public static final String CLIENT_RECONNECT_JITTER_MIN = "rpc.client.reconnect.jitter.minSeconds";
    public static final String CLIENT_RECONNECT_JITTER_MAX = "rpc.client.reconnect.jitter.maxSeconds";
    public static final String CLIENT_DISCOVERY_PREHEAT_ENABLED = "rpc.client.discovery.preheat.enabled";
    public static final String CLIENT_DISCOVERY_PREHEAT_SERVICES = "rpc.client.discovery.preheat.services";
    public static final String CLIENT_DISCOVERY_CACHE_TTL = "rpc.client.discovery.cacheTtlMillis";
    public static final String CLIENT_DISCOVERY_ALLOW_STALE = "rpc.client.discovery.allowStaleOnFailure";
    public static final String FILTER_CONSUMER = "rpc.filter.consumer";
    public static final String FILTER_INVOKER = "rpc.filter.invoker";
    public static final String FILTER_PROVIDER = "rpc.filter.provider";
    public static final String FILTER_ORDER_PREFIX = "rpc.filter.order.";
    public static final String CLIENT_DEGRADATION_POLICY = "rpc.client.degradation.policy";
    public static final String CLIENT_DEGRADATION_DEFAULT_VALUE_PREFIX = "rpc.client.degradation.defaultValue.";
    public static final String CLIENT_ENABLE_DEGRADATION = "rpc.client.enableDegradation";
    public static final String CLIENT_DEGRADATION_FAILURE_THRESHOLD = "rpc.client.degradationFailureThreshold";
    public static final String CLIENT_RATE_LIMIT_ENABLED = "rpc.client.rateLimit.enabled";
    public static final String CLIENT_RATE_LIMIT_PERMITS_PER_SECOND = "rpc.client.rateLimit.permitsPerSecond";
    public static final String CLIENT_CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD = "rpc.client.circuitBreaker.failureRateThreshold";
    public static final String CLIENT_CIRCUIT_BREAKER_MIN_NUMBER_OF_CALLS = "rpc.client.circuitBreaker.minNumberOfCalls";
    public static final String CLIENT_CIRCUIT_BREAKER_WAIT_DURATION_OPEN_MILLIS = "rpc.client.circuitBreaker.waitDurationInOpenStateMillis";
    public static final String CLIENT_CIRCUIT_BREAKER_HALF_OPEN_CALLS = "rpc.client.circuitBreaker.permittedHalfOpenCalls";

    private RpcConfigKeys() {
    }
}

