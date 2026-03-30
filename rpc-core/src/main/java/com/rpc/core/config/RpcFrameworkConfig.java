package com.rpc.core.config;

import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.MethodConfig;
import com.rpc.core.registry.RegistryType;
import com.rpc.core.transport.TransportType;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class RpcFrameworkConfig {
    private TransportType transportType = TransportType.NETTY;
    private String serializer = "protobuf";
    private String loadBalancer = "random";

    private RegistryType registryType = RegistryType.ZOOKEEPER;
    private String registryAddress = "127.0.0.1:2181";
    private int registryTimeout = 5000;

    private String serverHost = "127.0.0.1";
    private int serverPort = 8080;
    private List<String> serverScanPackages = new ArrayList<>();
    private boolean serverAutoRegisterAnnotatedServices = true;
    private int bossThreads = 1;
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;
    private int bizCoreThreads = Runtime.getRuntime().availableProcessors();
    private int bizMaxThreads = Runtime.getRuntime().availableProcessors() * 2;
    private int bizQueueCapacity = 1000;
    private boolean serverRateLimitEnabled = false;
    private int serverRateLimitPermitsPerSecond = 200;
    private boolean serverDegradationEnabled = false;
    private int shutdownTimeout = 10;
    private int serverReaderIdleTime = 30000;
    private int serverWriterIdleTime = 0;
    private int serverAllIdleTime = 0;

    private int connectTimeout = 5000;
    private int readTimeout = 10000;
    private int heartbeatInterval = 30000;
    private int writerIdleTime = 30000;
    private int readerIdleTime = 10000;
    private int retryTimes = 3;
    private ClusterStrategy clusterStrategy = ClusterStrategy.FAIL_OVER;
    private List<MethodConfig> methodConfigs = new ArrayList<>();
    private boolean reconnectEnabled = true;
    private int reconnectMaxRetryTimes = 5;
    private int reconnectInitialDelaySeconds = 2;
    private int reconnectMaxDelaySeconds = 60;
    private boolean reconnectJitterEnabled = true;
    private int reconnectJitterMinSeconds = 0;
    private int reconnectJitterMaxSeconds = 1;
    private boolean discoveryPreheatEnabled = false;
    private List<String> discoveryPreheatServices = new ArrayList<>();
    private long discoveryCacheTtlMillis = 30000L;
    private boolean discoveryAllowStaleOnFailure = true;
    private List<String> consumerFilters = new ArrayList<>();
    private List<String> invokerFilters = new ArrayList<>();
    private List<String> providerFilters = new ArrayList<>();
    private Map<String, Integer> filterOrders = new HashMap<>();
    private String consumerDegradationPolicy = "failFast";
    private Map<String, String> consumerDegradationDefaultValues = new HashMap<>();
    private boolean enableDegradation = false;
    private int degradationFailureThreshold = 10;
    private boolean rateLimitEnabled = false;
    private int rateLimitPermitsPerSecond = 100;
    private float circuitBreakerFailureRateThreshold = 50.0f;
    private int circuitBreakerMinNumberOfCalls = 10;
    private long circuitBreakerWaitDurationInOpenStateMillis = 30000L;
    private int circuitBreakerPermittedHalfOpenCalls = 5;
    private String serverDegradationPolicy = "failFast";
    private Map<String, String> serverDegradationDefaultValues = new HashMap<>();
}

