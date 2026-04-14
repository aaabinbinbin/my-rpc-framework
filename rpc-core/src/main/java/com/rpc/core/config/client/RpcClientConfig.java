package com.rpc.core.config.client;

import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.resilience.DegradationPolicy;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.MethodConfig;
import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.extension.loadbalance.factory.LoadBalancerFactory;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.factory.SerializerFactory;
import com.rpc.core.transport.TransportType;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * consumer 侧运行时配置。
 *
 * 所处阶段：RpcFrameworkConfig 已经完成加载和绑定，当前对象下沉给 Netty client、
 * 调用编排器、连接池、重试器、熔断器和服务目录使用。
 *
 * 注意事项：
 * - 这里的默认值决定了用户最小化 application.yml 时的运行行为。
 * - 背压类参数不能无限大，否则 provider 慢或网络抖动时客户端会先被拖垮。
 */
@Data
@Builder
public class RpcClientConfig {
    @Builder.Default
    /** 客户端传输类型，当前主路径使用 Netty。 */
    private TransportType transportType = TransportType.NETTY;

    @Builder.Default
    /** TCP 建连超时时间，影响首次连接和重连失败速度。 */
    private int connectTimeout = 5000;

    @Builder.Default
    /** 单次 RPC 请求读取响应的超时时间，可被方法级配置覆盖。 */
    private int readTimeout = 10000;

    @Builder.Default
    /** 客户端写空闲后发送心跳的间隔。 */
    private int heartbeatInterval = 30000;

    @Builder.Default
    /** 单连接最大在途请求数，防止一个 Channel 被无限压入请求。 */
    private int maxInflightRequestsPerConnection = 256;

    @Builder.Default
    /** 每个 provider 地址最多建立多少条连接。 */
    private int maxConnectionsPerAddress = 2;

    @Builder.Default
    /** 当前客户端所有 provider 地址的连接总预算。 */
    private int maxTotalConnections = 128;

    @Builder.Default
    /** 客户端 pending 请求总上限，超过后快速失败为 CLIENT_BUSY。 */
    private int maxPendingRequests = 10_000;

    @Builder.Default
    /** 空闲连接存活时间；连接仍有 inflight 请求时不会被回收。 */
    private long idleConnectionTtlMillis = 60_000L;

    @Builder.Default
    /** 空闲连接回收扫描周期。 */
    private long idleConnectionEvictIntervalMillis = 30_000L;

    @Builder.Default
    private int writerIdleTime = 30000;

    @Builder.Default
    private int readerIdleTime = 10000;

    @Builder.Default
    /** 默认负载均衡器；方法级配置可以覆盖。 */
    private LoadBalancer loadBalancer = LoadBalancerFactory.getDefaultLoadBalancer();

    @Builder.Default
    /** 默认序列化器名称；协议头中会写入对应 serializerType。 */
    private String serializerName = "protobuf";

    @Builder.Default
    /** 默认重试次数，实际是否重试还受 cluster 策略和异常类型影响。 */
    private int retryTimes = 3;

    @Builder.Default
    /** 默认集群容错策略，例如 failover 或 failfast。 */
    private ClusterStrategy clusterStrategy = ClusterStrategy.FAIL_OVER;

    @Builder.Default
    /** 方法级配置列表，用于覆盖超时、重试、序列化、负载均衡等默认值。 */
    private List<MethodConfig> methodConfigs = new ArrayList<>();

    @Builder.Default
    private boolean reconnectEnabled = true;

    @Builder.Default
    private int reconnectMaxRetryTimes = 5;

    @Builder.Default
    private int reconnectInitialDelaySeconds = 2;

    @Builder.Default
    private int reconnectMaxDelaySeconds = 60;

    @Builder.Default
    private boolean reconnectJitterEnabled = true;

    @Builder.Default
    private int reconnectJitterMinSeconds = 0;

    @Builder.Default
    private int reconnectJitterMaxSeconds = 1;

    @Builder.Default
    /** 是否在启动阶段预热服务目录，降低首次调用服务发现延迟。 */
    private boolean discoveryPreheatEnabled = false;

    @Builder.Default
    private List<String> discoveryPreheatServices = new ArrayList<>();

    @Builder.Default
    /** 服务发现本地缓存 TTL；调用路径优先读缓存，避免每次访问注册中心。 */
    private long discoveryCacheTtlMillis = 30000L;

    @Builder.Default
    /** 注册中心短暂失败时是否允许使用旧快照继续调用。 */
    private boolean discoveryAllowStaleOnFailure = true;

    @Builder.Default
    private DegradationPolicy degradationPolicy = null;

    @Builder.Default
    private boolean enableDegradation = false;

    @Builder.Default
    /** consumer 侧限流开关，保护当前客户端和下游服务。 */
    private boolean rateLimitEnabled = false;

    @Builder.Default
    private int rateLimitPermitsPerSecond = 100;

    @Builder.Default
    /** 熔断器打开阈值，单位是失败百分比。 */
    private float circuitBreakerFailureRateThreshold = 50.0f;

    @Builder.Default
    private int circuitBreakerMinNumberOfCalls = 10;

    @Builder.Default
    private long circuitBreakerWaitDurationInOpenStateMillis = 30000L;

    @Builder.Default
    private int circuitBreakerPermittedHalfOpenCalls = 5;

    public static RpcClientConfig custom() {
        return RpcClientConfig.builder().build();
    }

    /**
     * 从全局框架配置转换成 consumer 侧运行时配置。
     *
     * 这样 bootstrap 不需要理解每个 Netty/连接池/治理参数的细节，
     * 只负责把配置交给客户端运行时。
     */
    public static RpcClientConfig fromFrameworkConfig(RpcFrameworkConfig frameworkConfig,
                                                      DegradationPolicy degradationPolicy) {
        return RpcClientConfig.builder()
                .transportType(frameworkConfig.getTransportType())
                .connectTimeout(frameworkConfig.getConnectTimeout())
                .readTimeout(frameworkConfig.getReadTimeout())
                .heartbeatInterval(frameworkConfig.getHeartbeatInterval())
                .maxInflightRequestsPerConnection(frameworkConfig.getMaxInflightRequestsPerConnection())
                .maxConnectionsPerAddress(frameworkConfig.getMaxConnectionsPerAddress())
                .maxTotalConnections(frameworkConfig.getMaxTotalConnections())
                .maxPendingRequests(frameworkConfig.getMaxPendingRequests())
                .idleConnectionTtlMillis(frameworkConfig.getIdleConnectionTtlMillis())
                .idleConnectionEvictIntervalMillis(frameworkConfig.getIdleConnectionEvictIntervalMillis())
                .writerIdleTime(frameworkConfig.getWriterIdleTime())
                .readerIdleTime(frameworkConfig.getReaderIdleTime())
                .retryTimes(frameworkConfig.getRetryTimes())
                .clusterStrategy(frameworkConfig.getClusterStrategy())
                .methodConfigs(frameworkConfig.getMethodConfigs())
                .reconnectEnabled(frameworkConfig.isReconnectEnabled())
                .reconnectMaxRetryTimes(frameworkConfig.getReconnectMaxRetryTimes())
                .reconnectInitialDelaySeconds(frameworkConfig.getReconnectInitialDelaySeconds())
                .reconnectMaxDelaySeconds(frameworkConfig.getReconnectMaxDelaySeconds())
                .reconnectJitterEnabled(frameworkConfig.isReconnectJitterEnabled())
                .reconnectJitterMinSeconds(frameworkConfig.getReconnectJitterMinSeconds())
                .reconnectJitterMaxSeconds(frameworkConfig.getReconnectJitterMaxSeconds())
                .discoveryPreheatEnabled(frameworkConfig.isDiscoveryPreheatEnabled())
                .discoveryPreheatServices(frameworkConfig.getDiscoveryPreheatServices())
                .discoveryCacheTtlMillis(frameworkConfig.getDiscoveryCacheTtlMillis())
                .discoveryAllowStaleOnFailure(frameworkConfig.isDiscoveryAllowStaleOnFailure())
                .degradationPolicy(degradationPolicy)
                .enableDegradation(frameworkConfig.isEnableDegradation())
                .rateLimitEnabled(frameworkConfig.isRateLimitEnabled())
                .rateLimitPermitsPerSecond(frameworkConfig.getRateLimitPermitsPerSecond())
                .circuitBreakerFailureRateThreshold(frameworkConfig.getCircuitBreakerFailureRateThreshold())
                .circuitBreakerMinNumberOfCalls(frameworkConfig.getCircuitBreakerMinNumberOfCalls())
                .circuitBreakerWaitDurationInOpenStateMillis(frameworkConfig.getCircuitBreakerWaitDurationInOpenStateMillis())
                .circuitBreakerPermittedHalfOpenCalls(frameworkConfig.getCircuitBreakerPermittedHalfOpenCalls())
                .loadBalancer(LoadBalancerFactory.getLoadBalancer(frameworkConfig.getLoadBalancer()))
                .serializerName(frameworkConfig.getSerializer())
                .build();
    }

    /** 根据 serializerName 从 SPI/factory 中解析真实序列化器实例。 */
    public Serializer resolveSerializer() {
        return SerializerFactory.getSerializer(serializerName);
    }
}

