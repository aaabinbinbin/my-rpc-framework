package com.rpc.core.config.framework;

import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.MethodConfig;
import com.rpc.core.registry.RegistryType;
import com.rpc.core.transport.TransportType;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RPC 框架总配置对象。
 *
 * 这个类是整个框架运行时最核心的配置入口之一，
 * consumer、provider、Spring 集成、transport、治理能力都会从这里读取参数。
 *
 * 可以把这些字段按几大类去理解：
 * 1. 基础通信配置：传输方式、序列化器、负载均衡器、注册中心。
 * 2. provider 服务端配置：host、port、线程池、扫描包等。
 * 3. consumer 调用配置：超时、重试、集群策略、心跳、重连等。
 * 4. 过滤器与治理配置：限流、熔断、降级、过滤器链顺序等。
 */
@Data
public class RpcFrameworkConfig {
    /** 传输层实现类型，例如 NETTY。 */
    private TransportType transportType = TransportType.NETTY;
    /** 默认序列化器名称。 */
    private String serializer = "protobuf";
    /** 默认负载均衡器名称。 */
    private String loadBalancer = "random";

    /** 注册中心类型，例如 ZooKeeper。 */
    private RegistryType registryType = RegistryType.ZOOKEEPER;
    /** 注册中心地址。 */
    private String registryAddress = "127.0.0.1:2181";
    /** 注册中心交互超时。 */
    private int registryTimeout = 5000;

    /** provider 对外监听的 host。 */
    private String serverHost = "127.0.0.1";
    /** provider 对外监听的端口。 */
    private int serverPort = 8080;
    /** provider 自动扫描 @RpcService 的包路径列表。 */
    private List<String> serverScanPackages = new ArrayList<>();
    /** 是否允许 provider 启动时自动注册注解服务。 */
    private boolean serverAutoRegisterAnnotatedServices = true;
    /** Netty boss 线程数。 */
    private int bossThreads = 1;
    /** Netty worker 线程数。 */
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;
    /** provider 业务线程池核心线程数。 */
    private int bizCoreThreads = Runtime.getRuntime().availableProcessors();
    /** provider 业务线程池最大线程数。 */
    private int bizMaxThreads = Runtime.getRuntime().availableProcessors() * 2;
    /** provider 业务线程池队列容量。 */
    private int bizQueueCapacity = 1000;
    /** provider 侧限流开关。 */
    private boolean serverRateLimitEnabled = false;
    /** provider 侧每秒限流许可数。 */
    private int serverRateLimitPermitsPerSecond = 200;
    /** provider 侧降级开关。 */
    private boolean serverDegradationEnabled = false;
    /** 优雅停机超时时间。 */
    private int shutdownTimeout = 10;
    /** provider 读空闲时间。 */
    private int serverReaderIdleTime = 30000;
    /** provider 写空闲时间。 */
    private int serverWriterIdleTime = 0;
    /** provider 全空闲时间。 */
    private int serverAllIdleTime = 0;

    /** consumer 连接超时。 */
    private int connectTimeout = 5000;
    /** consumer 默认读取超时。 */
    private int readTimeout = 10000;
    /** consumer 心跳发送间隔。 */
    private int heartbeatInterval = 30000;
    /** 单连接最大在途请求数，用于限制一个 Channel 上同时等待响应的请求数量。 */
    private int maxInflightRequestsPerConnection = 256;
    /** 每个 provider 地址允许建立的最大连接数。 */
    private int maxConnectionsPerAddress = 2;
    /** 当前 consumer 允许维护的总连接数预算。 */
    private int maxTotalConnections = 128;
    /** 当前 consumer 允许等待响应的 pending 请求总量。 */
    private int maxPendingRequests = 10_000;
    /** 空闲连接存活时间，超过后且无在途请求才会被连接池回收。 */
    private long idleConnectionTtlMillis = 60_000L;
    /** 空闲连接回收任务的扫描间隔。 */
    private long idleConnectionEvictIntervalMillis = 30_000L;
    /** consumer 写空闲时间。 */
    private int writerIdleTime = 30000;
    /** consumer 读空闲时间。 */
    private int readerIdleTime = 10000;
    /** 默认请求重试次数。 */
    private int retryTimes = 3;
    /** 默认集群容错策略。 */
    private ClusterStrategy clusterStrategy = ClusterStrategy.FAIL_OVER;
    /** 方法级配置列表，用于覆盖全局默认调用参数。 */
    private List<MethodConfig> methodConfigs = new ArrayList<>();
    /** 是否启用断线重连。 */
    private boolean reconnectEnabled = true;
    /** 最大重连次数。 */
    private int reconnectMaxRetryTimes = 5;
    /** 重连初始等待时间。 */
    private int reconnectInitialDelaySeconds = 2;
    /** 重连最大等待时间。 */
    private int reconnectMaxDelaySeconds = 60;
    /** 是否启用重连抖动，避免大量客户端同时重连。 */
    private boolean reconnectJitterEnabled = true;
    /** 重连抖动最小秒数。 */
    private int reconnectJitterMinSeconds = 0;
    /** 重连抖动最大秒数。 */
    private int reconnectJitterMaxSeconds = 1;
    /** 是否在启动阶段预热服务目录。 */
    private boolean discoveryPreheatEnabled = false;
    /** 需要预热的服务列表。 */
    private List<String> discoveryPreheatServices = new ArrayList<>();
    /** 服务目录缓存 TTL。 */
    private long discoveryCacheTtlMillis = 30000L;
    /** 注册中心失败时是否允许使用过期缓存。 */
    private boolean discoveryAllowStaleOnFailure = true;
    /** consumer 阶段过滤器列表。 */
    private List<String> consumerFilters = new ArrayList<>();
    /** invoker 阶段过滤器列表。 */
    private List<String> invokerFilters = new ArrayList<>();
    /** provider 阶段过滤器列表。 */
    private List<String> providerFilters = new ArrayList<>();
    /** 过滤器顺序配置。 */
    private Map<String, Integer> filterOrders = new HashMap<>();
    /** consumer 侧降级策略名称。 */
    private String consumerDegradationPolicy = "failFast";
    /** consumer 侧降级默认值配置。 */
    private Map<String, String> consumerDegradationDefaultValues = new HashMap<>();
    /** 是否启用 consumer 侧降级。 */
    private boolean enableDegradation = false;
    /** consumer 限流开关。 */
    private boolean rateLimitEnabled = false;
    /** consumer 每秒限流许可数。 */
    private int rateLimitPermitsPerSecond = 100;
    /** 熔断器失败率阈值。 */
    private float circuitBreakerFailureRateThreshold = 50.0f;
    /** 熔断器最小统计调用数。 */
    private int circuitBreakerMinNumberOfCalls = 10;
    /** 熔断打开后的等待时间。 */
    private long circuitBreakerWaitDurationInOpenStateMillis = 30000L;
    /** 半开状态允许通过的调用数。 */
    private int circuitBreakerPermittedHalfOpenCalls = 5;
    /** provider 侧降级策略名称。 */
    private String serverDegradationPolicy = "failFast";
    /** provider 侧降级默认值配置。 */
    private Map<String, String> serverDegradationDefaultValues = new HashMap<>();
}
