package com.rpc.spring.boot;

import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.MethodConfig;
import com.rpc.core.invoke.invocation.CircuitBreakerScope;
import com.rpc.core.registry.RegistryType;
import com.rpc.core.transport.TransportType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot 场景下的 RPC 主配置绑定对象。
 *
 * 所处阶段：Spring Boot 启动早期由 ConfigurationProperties 机制把 application.yml/properties 绑定到当前对象。
 * 主要职责：承接用户侧可读性更强的 rpc.* 配置，并在 toFrameworkConfig() 中转换成 rpc-core 统一使用的
 * RpcFrameworkConfig，避免 core 模块直接依赖 Spring Boot。
 *
 * 注意事项：这里的默认值决定了用户最小接入成本，只有非默认诉求才需要在 application.yml 中显式配置。
 */
@ConfigurationProperties(prefix = "rpc")
public class RpcBootFrameworkProperties {
    /** 传输实现类型，默认使用 netty，对应 core 层 TransportType。 */
    private String transport = "netty";
    /** 序列化器名称，默认使用 protobuf；如果 SPI 中没有对应实现，启动时会在 core 层失败暴露。 */
    private String serializer = "protobuf";
    /** 负载均衡策略名称，默认 random，最终由 core 层负载均衡工厂解析。 */
    private String loadbalancer = "random";
    /** 注册中心配置组，承载注册中心类型、地址和连接超时时间。 */
    private Registry registry = new Registry();
    /** 服务端配置组，只有 provider 应用真正发布服务时才会使用其中的端口、线程池等参数。 */
    private Server server = new Server();
    /** 客户端配置组，consumer 调用链会使用连接池、超时、重试、熔断等参数。 */
    private Client client = new Client();
    /** 过滤器配置组，用于控制 consumer、invoker、provider 三段过滤器链。 */
    private Filter filter = new Filter();

    /**
     * 将 Spring Boot 配置对象转换为 rpc-core 层统一配置。
     *
     * 边界处理：集合和 Map 会复制成新的对象传入 core，避免后续 Spring 配置对象被外部修改时影响运行时配置。
     *
     * @return core 层统一配置对象
     */
    public RpcFrameworkConfig toFrameworkConfig() {
        // Boot 配置对象的职责是把 application.yml/properties 中较松散的结构
        // 转成 core 层统一使用的 RpcFrameworkConfig，避免 core 直接依赖 Spring API。
        RpcFrameworkConfig config = new RpcFrameworkConfig();
        config.setTransportType(TransportType.from(transport));
        config.setSerializer(serializer);
        config.setLoadBalancer(loadbalancer);
        config.setRegistryType(RegistryType.from(registry.type));
        config.setRegistryAddress(registry.address);
        config.setRegistryTimeout(registry.timeout);
        config.setServerHost(server.host);
        config.setServerPort(server.port);
        config.setServerScanPackages(new ArrayList<>(server.scanPackages));
        config.setServerAutoRegisterAnnotatedServices(server.autoRegisterAnnotatedServices);
        config.setBossThreads(server.bossThreads);
        config.setWorkerThreads(server.workerThreads);
        config.setBizCoreThreads(server.biz.coreThreads);
        config.setBizMaxThreads(server.biz.maxThreads);
        config.setBizQueueCapacity(server.biz.queueCapacity);
        config.setServerRateLimitEnabled(server.rateLimit.enabled);
        config.setServerRateLimitPermitsPerSecond(server.rateLimit.permitsPerSecond);
        config.setServerDegradationEnabled(server.degradation.enabled);
        config.setServerDegradationPolicy(server.degradation.policy);
        config.setServerDegradationDefaultValues(new HashMap<>(server.degradation.defaultValue));
        config.setShutdownTimeout(server.shutdownTimeout);
        config.setServerReaderIdleTime(server.readerIdleTime);
        config.setServerWriterIdleTime(server.writerIdleTime);
        config.setServerAllIdleTime(server.allIdleTime);
        config.setConnectTimeout(client.connectTimeout);
        config.setReadTimeout(client.readTimeout);
        config.setHeartbeatInterval(client.heartbeatInterval);
        config.setMaxInflightRequestsPerConnection(client.maxInflightRequestsPerConnection);
        config.setMaxConnectionsPerAddress(client.maxConnectionsPerAddress);
        config.setMaxTotalConnections(client.maxTotalConnections);
        config.setMaxPendingRequests(client.maxPendingRequests);
        config.setIdleConnectionTtlMillis(client.idleConnectionTtlMillis);
        config.setIdleConnectionEvictIntervalMillis(client.idleConnectionEvictIntervalMillis);
        config.setWriterIdleTime(client.writerIdleTime);
        config.setReaderIdleTime(client.readerIdleTime);
        config.setRetryTimes(client.retryTimes);
        config.setClusterStrategy(ClusterStrategy.from(client.cluster));
        config.setMethodConfigs(client.methods.stream()
                .map(ClientMethod::toMethodConfig)
                .toList());
        config.setReconnectEnabled(client.reconnect.enabled);
        config.setReconnectMaxRetryTimes(client.reconnect.maxRetryTimes);
        config.setReconnectInitialDelaySeconds(client.reconnect.initialDelaySeconds);
        config.setReconnectMaxDelaySeconds(client.reconnect.maxDelaySeconds);
        config.setReconnectJitterEnabled(client.reconnect.jitter.enabled);
        config.setReconnectJitterMinSeconds(client.reconnect.jitter.minSeconds);
        config.setReconnectJitterMaxSeconds(client.reconnect.jitter.maxSeconds);
        config.setDiscoveryPreheatEnabled(client.discovery.preheat.enabled);
        config.setDiscoveryPreheatServices(new ArrayList<>(client.discovery.preheat.services));
        config.setDiscoveryCacheTtlMillis(client.discovery.cacheTtlMillis);
        config.setDiscoveryAllowStaleOnFailure(client.discovery.allowStaleOnFailure);
        config.setConsumerFilters(new ArrayList<>(filter.consumer));
        config.setInvokerFilters(new ArrayList<>(filter.invoker));
        config.setProviderFilters(new ArrayList<>(filter.provider));
        config.setFilterOrders(new HashMap<>(filter.order));
        config.setConsumerDegradationPolicy(client.degradation.policy);
        config.setConsumerDegradationDefaultValues(new HashMap<>(client.degradation.defaultValue));
        config.setEnableDegradation(client.enableDegradation);
        config.setRateLimitEnabled(client.rateLimit.enabled);
        config.setRateLimitPermitsPerSecond(client.rateLimit.permitsPerSecond);
        config.setCircuitBreakerFailureRateThreshold(client.circuitBreaker.failureRateThreshold);
        config.setCircuitBreakerMinNumberOfCalls(client.circuitBreaker.minNumberOfCalls);
        config.setCircuitBreakerWaitDurationInOpenStateMillis(client.circuitBreaker.waitDurationInOpenStateMillis);
        config.setCircuitBreakerPermittedHalfOpenCalls(client.circuitBreaker.permittedHalfOpenCalls);
        return config;
    }

    /** @return 传输实现类型 */
    public String getTransport() { return transport; }
    /** @param transport 传输实现类型 */
    public void setTransport(String transport) { this.transport = transport; }
    /** @return 序列化器名称 */
    public String getSerializer() { return serializer; }
    /** @param serializer 序列化器名称 */
    public void setSerializer(String serializer) { this.serializer = serializer; }
    /** @return 负载均衡策略名称 */
    public String getLoadbalancer() { return loadbalancer; }
    /** @param loadbalancer 负载均衡策略名称 */
    public void setLoadbalancer(String loadbalancer) { this.loadbalancer = loadbalancer; }
    /** @return 注册中心配置组 */
    public Registry getRegistry() { return registry; }
    /** @param registry 注册中心配置组 */
    public void setRegistry(Registry registry) { this.registry = registry; }
    /** @return 服务端配置组 */
    public Server getServer() { return server; }
    /** @param server 服务端配置组 */
    public void setServer(Server server) { this.server = server; }
    /** @return 客户端配置组 */
    public Client getClient() { return client; }
    /** @param client 客户端配置组 */
    public void setClient(Client client) { this.client = client; }
    /** @return 过滤器配置组 */
    public Filter getFilter() { return filter; }
    /** @param filter 过滤器配置组 */
    public void setFilter(Filter filter) { this.filter = filter; }

    /**
     * 注册中心配置组。
     *
     * 主要职责：描述服务注册与发现依赖的后端类型、地址和连接超时时间。
     */
    public static class Registry {
        /** 注册中心类型，默认 zookeeper。 */
        private String type = "zookeeper";
        /** 注册中心地址，格式通常为 host:port。 */
        private String address = "127.0.0.1:2181";
        /** 注册中心连接等待超时时间，单位毫秒。 */
        private int timeout = 15000;
        /** @return 注册中心类型 */
        public String getType() { return type; }
        /** @param type 注册中心类型 */
        public void setType(String type) { this.type = type; }
        /** @return 注册中心地址 */
        public String getAddress() { return address; }
        /** @param address 注册中心地址 */
        public void setAddress(String address) { this.address = address; }
        /** @return 注册中心连接等待超时时间，单位毫秒 */
        public int getTimeout() { return timeout; }
        /** @param timeout 注册中心连接等待超时时间，单位毫秒 */
        public void setTimeout(int timeout) { this.timeout = timeout; }
    }

    /**
     * Provider 侧服务端配置组。
     *
     * 所处阶段：RpcSpringManager 发布 @RpcService 时会将这些参数交给 provider bootstrap 和 Netty server。
     */
    public static class Server {
        /** 服务端监听主机，默认本机地址。 */
        private String host = "127.0.0.1";
        /** 服务端监听端口，默认 8080；实际项目中通常需要按应用显式配置。 */
        private int port = 8080;
        /** core 层注解扫描包；Spring 场景通常由 Spring 扫描接管。 */
        private List<String> scanPackages = new ArrayList<>();
        /** 是否允许 core 层自动注册 @RpcService；Spring 场景会由 RpcSpringManager 主动关闭以防重复注册。 */
        private boolean autoRegisterAnnotatedServices = true;
        /** Netty boss 线程数，负责接收连接。 */
        private int bossThreads = 1;
        /** Netty worker 线程数，负责网络读写事件。 */
        private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;
        /** 服务端业务线程池配置，用于承载实际方法调用。 */
        private Biz biz = new Biz();
        /** Provider 侧限流配置。 */
        private RateLimit rateLimit = new RateLimit();
        /** Provider 侧降级配置。 */
        private Degradation degradation = new Degradation();
        /** 优雅停机等待时间，单位秒。 */
        private int shutdownTimeout = 10;
        /** Netty 读空闲检测时间，单位毫秒。 */
        private int readerIdleTime = 30000;
        /** Netty 写空闲检测时间，单位毫秒；0 表示不启用。 */
        private int writerIdleTime = 0;
        /** Netty 读写全空闲检测时间，单位毫秒；0 表示不启用。 */
        private int allIdleTime = 0;
        /** @return 服务端监听主机 */
        public String getHost() { return host; }
        /** @param host 服务端监听主机 */
        public void setHost(String host) { this.host = host; }
        /** @return 服务端监听端口 */
        public int getPort() { return port; }
        /** @param port 服务端监听端口 */
        public void setPort(int port) { this.port = port; }
        /** @return core 层注解扫描包 */
        public List<String> getScanPackages() { return scanPackages; }
        /** @param scanPackages core 层注解扫描包 */
        public void setScanPackages(List<String> scanPackages) { this.scanPackages = scanPackages; }
        /** @return 是否允许 core 层自动注册注解服务 */
        public boolean isAutoRegisterAnnotatedServices() { return autoRegisterAnnotatedServices; }
        /** @param autoRegisterAnnotatedServices 是否允许 core 层自动注册注解服务 */
        public void setAutoRegisterAnnotatedServices(boolean autoRegisterAnnotatedServices) { this.autoRegisterAnnotatedServices = autoRegisterAnnotatedServices; }
        /** @return Netty boss 线程数 */
        public int getBossThreads() { return bossThreads; }
        /** @param bossThreads Netty boss 线程数 */
        public void setBossThreads(int bossThreads) { this.bossThreads = bossThreads; }
        /** @return Netty worker 线程数 */
        public int getWorkerThreads() { return workerThreads; }
        /** @param workerThreads Netty worker 线程数 */
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
        /** @return 服务端业务线程池配置 */
        public Biz getBiz() { return biz; }
        /** @param biz 服务端业务线程池配置 */
        public void setBiz(Biz biz) { this.biz = biz; }
        /** @return Provider 侧限流配置 */
        public RateLimit getRateLimit() { return rateLimit; }
        /** @param rateLimit Provider 侧限流配置 */
        public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
        /** @return Provider 侧降级配置 */
        public Degradation getDegradation() { return degradation; }
        /** @param degradation Provider 侧降级配置 */
        public void setDegradation(Degradation degradation) { this.degradation = degradation; }
        /** @return 优雅停机等待时间，单位秒 */
        public int getShutdownTimeout() { return shutdownTimeout; }
        /** @param shutdownTimeout 优雅停机等待时间，单位秒 */
        public void setShutdownTimeout(int shutdownTimeout) { this.shutdownTimeout = shutdownTimeout; }
        /** @return 读空闲检测时间，单位毫秒 */
        public int getReaderIdleTime() { return readerIdleTime; }
        /** @param readerIdleTime 读空闲检测时间，单位毫秒 */
        public void setReaderIdleTime(int readerIdleTime) { this.readerIdleTime = readerIdleTime; }
        /** @return 写空闲检测时间，单位毫秒 */
        public int getWriterIdleTime() { return writerIdleTime; }
        /** @param writerIdleTime 写空闲检测时间，单位毫秒 */
        public void setWriterIdleTime(int writerIdleTime) { this.writerIdleTime = writerIdleTime; }
        /** @return 读写全空闲检测时间，单位毫秒 */
        public int getAllIdleTime() { return allIdleTime; }
        /** @param allIdleTime 读写全空闲检测时间，单位毫秒 */
        public void setAllIdleTime(int allIdleTime) { this.allIdleTime = allIdleTime; }
    }

    /**
     * 服务端业务线程池配置。
     *
     * 主要职责：把耗时业务方法从 Netty IO 线程中隔离出来，提升服务端抗并发能力。
     */
    public static class Biz {
        /** 业务线程池核心线程数。 */
        private int coreThreads = Runtime.getRuntime().availableProcessors();
        /** 业务线程池最大线程数。 */
        private int maxThreads = Runtime.getRuntime().availableProcessors() * 2;
        /** 业务线程池等待队列容量，超过后会触发拒绝策略。 */
        private int queueCapacity = 1000;
        /** @return 业务线程池核心线程数 */
        public int getCoreThreads() { return coreThreads; }
        /** @param coreThreads 业务线程池核心线程数 */
        public void setCoreThreads(int coreThreads) { this.coreThreads = coreThreads; }
        /** @return 业务线程池最大线程数 */
        public int getMaxThreads() { return maxThreads; }
        /** @param maxThreads 业务线程池最大线程数 */
        public void setMaxThreads(int maxThreads) { this.maxThreads = maxThreads; }
        /** @return 业务线程池等待队列容量 */
        public int getQueueCapacity() { return queueCapacity; }
        /** @param queueCapacity 业务线程池等待队列容量 */
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    /**
     * 限流配置组。
     *
     * 使用阶段：Provider 或 Consumer 过滤器链执行时按配置决定是否限流。
     */
    public static class RateLimit {
        /** 是否启用限流。 */
        private boolean enabled;
        /** 每秒允许通过的请求数。 */
        private int permitsPerSecond = 100;
        /** @return 是否启用限流 */
        public boolean isEnabled() { return enabled; }
        /** @param enabled 是否启用限流 */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        /** @return 每秒允许通过的请求数 */
        public int getPermitsPerSecond() { return permitsPerSecond; }
        /** @param permitsPerSecond 每秒允许通过的请求数 */
        public void setPermitsPerSecond(int permitsPerSecond) { this.permitsPerSecond = permitsPerSecond; }
    }

    /**
     * 降级配置组。
     *
     * 主要职责：描述失败时是否允许返回默认值，以及默认值策略如何选择。
     */
    public static class Degradation {
        /** 是否启用降级。 */
        private boolean enabled;
        /** 降级策略名称，例如 failFast 或 defaultValue。 */
        private String policy = "failFast";
        /** 方法级默认降级值映射，key 通常是服务方法标识。 */
        private Map<String, String> defaultValue = new HashMap<>();
        /** @return 是否启用降级 */
        public boolean isEnabled() { return enabled; }
        /** @param enabled 是否启用降级 */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        /** @return 降级策略名称 */
        public String getPolicy() { return policy; }
        /** @param policy 降级策略名称 */
        public void setPolicy(String policy) { this.policy = policy; }
        /** @return 方法级默认降级值映射 */
        public Map<String, String> getDefaultValue() { return defaultValue; }
        /** @param defaultValue 方法级默认降级值映射 */
        public void setDefaultValue(Map<String, String> defaultValue) { this.defaultValue = defaultValue; }
    }

    /**
     * Consumer 侧客户端配置组。
     *
     * 所处阶段：@RpcReference 代理发起远程调用时，连接池、超时、重试、熔断等逻辑会读取这些配置。
     */
    public static class Client {
        /** 建立 TCP 连接的超时时间，单位毫秒。 */
        private int connectTimeout = 5000;
        /** 单次 RPC 调用等待响应的超时时间，单位毫秒。 */
        private int readTimeout = 10000;
        /** 客户端心跳发送间隔，单位毫秒。 */
        private int heartbeatInterval = 30000;
        /** 单连接最大并发在途请求数，用于防止单条连接被打满。 */
        private int maxInflightRequestsPerConnection = 256;
        /** 单个服务地址允许建立的最大连接数。 */
        private int maxConnectionsPerAddress = 2;
        /** 客户端全局最大连接数。 */
        private int maxTotalConnections = 128;
        /** 客户端全局最大待处理请求数，用于背压保护。 */
        private int maxPendingRequests = 10_000;
        /** 空闲连接存活时间，单位毫秒。 */
        private long idleConnectionTtlMillis = 60_000L;
        /** 空闲连接清理任务执行间隔，单位毫秒。 */
        private long idleConnectionEvictIntervalMillis = 30_000L;
        /** 写空闲检测时间，单位毫秒。 */
        private int writerIdleTime = 30000;
        /** 读空闲检测时间，单位毫秒。 */
        private int readerIdleTime = 10000;
        /** 默认重试次数，最终会被方法级配置覆盖。 */
        private int retryTimes = 3;
        /** 集群容错策略，默认 failover。 */
        private String cluster = "failover";
        /** 方法级配置列表，用于覆盖全局超时、重试、负载均衡等策略。 */
        private List<ClientMethod> methods = new ArrayList<>();
        /** 断线重连配置组。 */
        private Reconnect reconnect = new Reconnect();
        /** 服务发现缓存与预热配置组。 */
        private Discovery discovery = new Discovery();
        /** 是否启用 Consumer 侧降级。 */
        private boolean enableDegradation;
        /** Consumer 侧降级策略配置。 */
        private Degradation degradation = new Degradation();
        /** Consumer 侧限流配置。 */
        private RateLimit rateLimit = new RateLimit();
        /** Consumer 侧熔断器配置。 */
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
        /** @return 建立 TCP 连接的超时时间，单位毫秒 */
        public int getConnectTimeout() { return connectTimeout; }
        /** @param connectTimeout 建立 TCP 连接的超时时间，单位毫秒 */
        public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }
        /** @return 单次 RPC 调用等待响应的超时时间，单位毫秒 */
        public int getReadTimeout() { return readTimeout; }
        /** @param readTimeout 单次 RPC 调用等待响应的超时时间，单位毫秒 */
        public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }
        /** @return 客户端心跳发送间隔，单位毫秒 */
        public int getHeartbeatInterval() { return heartbeatInterval; }
        /** @param heartbeatInterval 客户端心跳发送间隔，单位毫秒 */
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        /** @return 单连接最大并发在途请求数 */
        public int getMaxInflightRequestsPerConnection() { return maxInflightRequestsPerConnection; }
        /** @param maxInflightRequestsPerConnection 单连接最大并发在途请求数 */
        public void setMaxInflightRequestsPerConnection(int maxInflightRequestsPerConnection) { this.maxInflightRequestsPerConnection = maxInflightRequestsPerConnection; }
        /** @return 单个服务地址最大连接数 */
        public int getMaxConnectionsPerAddress() { return maxConnectionsPerAddress; }
        /** @param maxConnectionsPerAddress 单个服务地址最大连接数 */
        public void setMaxConnectionsPerAddress(int maxConnectionsPerAddress) { this.maxConnectionsPerAddress = maxConnectionsPerAddress; }
        /** @return 客户端全局最大连接数 */
        public int getMaxTotalConnections() { return maxTotalConnections; }
        /** @param maxTotalConnections 客户端全局最大连接数 */
        public void setMaxTotalConnections(int maxTotalConnections) { this.maxTotalConnections = maxTotalConnections; }
        /** @return 客户端全局最大待处理请求数 */
        public int getMaxPendingRequests() { return maxPendingRequests; }
        /** @param maxPendingRequests 客户端全局最大待处理请求数 */
        public void setMaxPendingRequests(int maxPendingRequests) { this.maxPendingRequests = maxPendingRequests; }
        /** @return 空闲连接存活时间，单位毫秒 */
        public long getIdleConnectionTtlMillis() { return idleConnectionTtlMillis; }
        /** @param idleConnectionTtlMillis 空闲连接存活时间，单位毫秒 */
        public void setIdleConnectionTtlMillis(long idleConnectionTtlMillis) { this.idleConnectionTtlMillis = idleConnectionTtlMillis; }
        /** @return 空闲连接清理任务执行间隔，单位毫秒 */
        public long getIdleConnectionEvictIntervalMillis() { return idleConnectionEvictIntervalMillis; }
        /** @param idleConnectionEvictIntervalMillis 空闲连接清理任务执行间隔，单位毫秒 */
        public void setIdleConnectionEvictIntervalMillis(long idleConnectionEvictIntervalMillis) { this.idleConnectionEvictIntervalMillis = idleConnectionEvictIntervalMillis; }
        /** @return 写空闲检测时间，单位毫秒 */
        public int getWriterIdleTime() { return writerIdleTime; }
        /** @param writerIdleTime 写空闲检测时间，单位毫秒 */
        public void setWriterIdleTime(int writerIdleTime) { this.writerIdleTime = writerIdleTime; }
        /** @return 读空闲检测时间，单位毫秒 */
        public int getReaderIdleTime() { return readerIdleTime; }
        /** @param readerIdleTime 读空闲检测时间，单位毫秒 */
        public void setReaderIdleTime(int readerIdleTime) { this.readerIdleTime = readerIdleTime; }
        /** @return 默认重试次数 */
        public int getRetryTimes() { return retryTimes; }
        /** @param retryTimes 默认重试次数 */
        public void setRetryTimes(int retryTimes) { this.retryTimes = retryTimes; }
        /** @return 集群容错策略 */
        public String getCluster() { return cluster; }
        /** @param cluster 集群容错策略 */
        public void setCluster(String cluster) { this.cluster = cluster; }
        /** @return 方法级配置列表 */
        public List<ClientMethod> getMethods() { return methods; }
        /** @param methods 方法级配置列表 */
        public void setMethods(List<ClientMethod> methods) { this.methods = methods; }
        /** @return 断线重连配置组 */
        public Reconnect getReconnect() { return reconnect; }
        /** @param reconnect 断线重连配置组 */
        public void setReconnect(Reconnect reconnect) { this.reconnect = reconnect; }
        /** @return 服务发现缓存与预热配置组 */
        public Discovery getDiscovery() { return discovery; }
        /** @param discovery 服务发现缓存与预热配置组 */
        public void setDiscovery(Discovery discovery) { this.discovery = discovery; }
        /** @return 是否启用 Consumer 侧降级 */
        public boolean isEnableDegradation() { return enableDegradation; }
        /** @param enableDegradation 是否启用 Consumer 侧降级 */
        public void setEnableDegradation(boolean enableDegradation) { this.enableDegradation = enableDegradation; }
        /** @return Consumer 侧降级策略配置 */
        public Degradation getDegradation() { return degradation; }
        /** @param degradation Consumer 侧降级策略配置 */
        public void setDegradation(Degradation degradation) { this.degradation = degradation; }
        /** @return Consumer 侧限流配置 */
        public RateLimit getRateLimit() { return rateLimit; }
        /** @param rateLimit Consumer 侧限流配置 */
        public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
        /** @return Consumer 侧熔断器配置 */
        public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
        /** @param circuitBreaker Consumer 侧熔断器配置 */
        public void setCircuitBreaker(CircuitBreaker circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    }

    /**
     * 单个方法维度的 Consumer 调用配置。
     *
     * 主要职责：允许热点方法或特殊方法覆盖全局的重试、超时、序列化、负载均衡、限流和熔断粒度。
     */
    public static class ClientMethod {
        /** 服务接口名或服务标识。 */
        private String serviceName;
        /** 方法名。 */
        private String methodName;
        /** 方法级重试次数；为空时继承全局配置。 */
        private Integer retryTimes;
        /** 方法级集群策略；为空时继承全局配置。 */
        private String clusterStrategy;
        /** 方法级读超时，单位毫秒；为空时继承全局配置。 */
        private Integer readTimeout;
        /** 方法级序列化器名称；为空时继承全局配置。 */
        private String serializerName;
        /** 方法级负载均衡名称；为空时继承全局配置。 */
        private String loadBalancerName;
        /** 方法级是否启用限流；为空时继承全局配置。 */
        private Boolean rateLimitEnabled;
        /** 方法级限流 QPS；为空时继承全局配置。 */
        private Integer rateLimitPermitsPerSecond;
        /** 方法级熔断统计粒度；为空时使用全局默认粒度。 */
        private String circuitBreakerScope;

        /**
         * 转换成 core 层方法配置。
         *
         * 边界处理：允许大部分字段为空，core 层解析器会按“方法级优先、全局兜底”的规则补齐。
         *
         * @return core 层 MethodConfig
         */
        public MethodConfig toMethodConfig() {
            // 方法级配置在这里完成一次显式转换，后续 core 层就只面向 MethodConfig 工作。
            return MethodConfig.builder()
                    .serviceName(serviceName)
                    .methodName(methodName)
                    .retryTimes(retryTimes)
                    .clusterStrategy(clusterStrategy == null ? null : ClusterStrategy.from(clusterStrategy))
                    .readTimeout(readTimeout)
                    .serializerName(serializerName)
                    .loadBalancerName(loadBalancerName)
                    .rateLimitEnabled(rateLimitEnabled)
                    .rateLimitPermitsPerSecond(rateLimitPermitsPerSecond)
                    .circuitBreakerScope(circuitBreakerScope == null ? null : CircuitBreakerScope.from(circuitBreakerScope))
                    .build();
        }

        /** @return 服务接口名或服务标识 */
        public String getServiceName() { return serviceName; }
        /** @param serviceName 服务接口名或服务标识 */
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        /** @return 方法名 */
        public String getMethodName() { return methodName; }
        /** @param methodName 方法名 */
        public void setMethodName(String methodName) { this.methodName = methodName; }
        /** @return 方法级重试次数 */
        public Integer getRetryTimes() { return retryTimes; }
        /** @param retryTimes 方法级重试次数 */
        public void setRetryTimes(Integer retryTimes) { this.retryTimes = retryTimes; }
        /** @return 方法级集群策略 */
        public String getClusterStrategy() { return clusterStrategy; }
        /** @param clusterStrategy 方法级集群策略 */
        public void setClusterStrategy(String clusterStrategy) { this.clusterStrategy = clusterStrategy; }
        /** @return 方法级读超时，单位毫秒 */
        public Integer getReadTimeout() { return readTimeout; }
        /** @param readTimeout 方法级读超时，单位毫秒 */
        public void setReadTimeout(Integer readTimeout) { this.readTimeout = readTimeout; }
        /** @return 方法级序列化器名称 */
        public String getSerializerName() { return serializerName; }
        /** @param serializerName 方法级序列化器名称 */
        public void setSerializerName(String serializerName) { this.serializerName = serializerName; }
        /** @return 方法级负载均衡名称 */
        public String getLoadBalancerName() { return loadBalancerName; }
        /** @param loadBalancerName 方法级负载均衡名称 */
        public void setLoadBalancerName(String loadBalancerName) { this.loadBalancerName = loadBalancerName; }
        /** @return 方法级是否启用限流 */
        public Boolean getRateLimitEnabled() { return rateLimitEnabled; }
        /** @param rateLimitEnabled 方法级是否启用限流 */
        public void setRateLimitEnabled(Boolean rateLimitEnabled) { this.rateLimitEnabled = rateLimitEnabled; }
        /** @return 方法级限流 QPS */
        public Integer getRateLimitPermitsPerSecond() { return rateLimitPermitsPerSecond; }
        /** @param rateLimitPermitsPerSecond 方法级限流 QPS */
        public void setRateLimitPermitsPerSecond(Integer rateLimitPermitsPerSecond) { this.rateLimitPermitsPerSecond = rateLimitPermitsPerSecond; }
        /** @return 方法级熔断统计粒度 */
        public String getCircuitBreakerScope() { return circuitBreakerScope; }
        /** @param circuitBreakerScope 方法级熔断统计粒度 */
        public void setCircuitBreakerScope(String circuitBreakerScope) { this.circuitBreakerScope = circuitBreakerScope; }
    }

    /**
     * 客户端断线重连配置。
     *
     * 主要职责：连接断开后控制重试次数、初始延迟、最大退避和随机抖动，避免大量客户端同时重连打爆服务端。
     */
    public static class Reconnect {
        /** 是否启用自动重连。 */
        private boolean enabled = true;
        /** 最大重连次数。 */
        private int maxRetryTimes = 5;
        /** 初始重连延迟，单位秒。 */
        private int initialDelaySeconds = 2;
        /** 最大重连延迟，单位秒。 */
        private int maxDelaySeconds = 60;
        /** 重连随机抖动配置。 */
        private Jitter jitter = new Jitter();
        /** @return 是否启用自动重连 */
        public boolean isEnabled() { return enabled; }
        /** @param enabled 是否启用自动重连 */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        /** @return 最大重连次数 */
        public int getMaxRetryTimes() { return maxRetryTimes; }
        /** @param maxRetryTimes 最大重连次数 */
        public void setMaxRetryTimes(int maxRetryTimes) { this.maxRetryTimes = maxRetryTimes; }
        /** @return 初始重连延迟，单位秒 */
        public int getInitialDelaySeconds() { return initialDelaySeconds; }
        /** @param initialDelaySeconds 初始重连延迟，单位秒 */
        public void setInitialDelaySeconds(int initialDelaySeconds) { this.initialDelaySeconds = initialDelaySeconds; }
        /** @return 最大重连延迟，单位秒 */
        public int getMaxDelaySeconds() { return maxDelaySeconds; }
        /** @param maxDelaySeconds 最大重连延迟，单位秒 */
        public void setMaxDelaySeconds(int maxDelaySeconds) { this.maxDelaySeconds = maxDelaySeconds; }
        /** @return 重连随机抖动配置 */
        public Jitter getJitter() { return jitter; }
        /** @param jitter 重连随机抖动配置 */
        public void setJitter(Jitter jitter) { this.jitter = jitter; }
    }

    /**
     * 重连随机抖动配置。
     *
     * 主要职责：在基础退避延迟上叠加随机偏移，降低雪崩式集中重连概率。
     */
    public static class Jitter {
        /** 是否启用随机抖动。 */
        private boolean enabled = true;
        /** 最小随机抖动秒数。 */
        private int minSeconds = 0;
        /** 最大随机抖动秒数。 */
        private int maxSeconds = 1;
        /** @return 是否启用随机抖动 */
        public boolean isEnabled() { return enabled; }
        /** @param enabled 是否启用随机抖动 */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        /** @return 最小随机抖动秒数 */
        public int getMinSeconds() { return minSeconds; }
        /** @param minSeconds 最小随机抖动秒数 */
        public void setMinSeconds(int minSeconds) { this.minSeconds = minSeconds; }
        /** @return 最大随机抖动秒数 */
        public int getMaxSeconds() { return maxSeconds; }
        /** @param maxSeconds 最大随机抖动秒数 */
        public void setMaxSeconds(int maxSeconds) { this.maxSeconds = maxSeconds; }
    }

    /**
     * 服务发现配置组。
     *
     * 主要职责：控制客户端是否预热服务列表、缓存多久以及注册中心异常时能否使用旧快照兜底。
     */
    public static class Discovery {
        /** 服务发现预热配置。 */
        private Preheat preheat = new Preheat();
        /** 服务发现缓存 TTL，单位毫秒。 */
        private long cacheTtlMillis = 30000L;
        /** 注册中心读取失败时是否允许使用旧缓存。 */
        private boolean allowStaleOnFailure = true;
        /** @return 服务发现预热配置 */
        public Preheat getPreheat() { return preheat; }
        /** @param preheat 服务发现预热配置 */
        public void setPreheat(Preheat preheat) { this.preheat = preheat; }
        /** @return 服务发现缓存 TTL，单位毫秒 */
        public long getCacheTtlMillis() { return cacheTtlMillis; }
        /** @param cacheTtlMillis 服务发现缓存 TTL，单位毫秒 */
        public void setCacheTtlMillis(long cacheTtlMillis) { this.cacheTtlMillis = cacheTtlMillis; }
        /** @return 注册中心读取失败时是否允许使用旧缓存 */
        public boolean isAllowStaleOnFailure() { return allowStaleOnFailure; }
        /** @param allowStaleOnFailure 注册中心读取失败时是否允许使用旧缓存 */
        public void setAllowStaleOnFailure(boolean allowStaleOnFailure) { this.allowStaleOnFailure = allowStaleOnFailure; }
    }

    /**
     * 服务发现预热配置。
     *
     * 使用阶段：consumer bootstrap 初始化时可提前拉取指定服务的实例列表，降低首次调用抖动。
     */
    public static class Preheat {
        /** 是否启用服务发现预热。 */
        private boolean enabled;
        /** 需要预热的服务名列表。 */
        private List<String> services = new ArrayList<>();
        /** @return 是否启用服务发现预热 */
        public boolean isEnabled() { return enabled; }
        /** @param enabled 是否启用服务发现预热 */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        /** @return 需要预热的服务名列表 */
        public List<String> getServices() { return services; }
        /** @param services 需要预热的服务名列表 */
        public void setServices(List<String> services) { this.services = services; }
    }

    /**
     * Consumer 侧熔断器配置。
     *
     * 主要职责：定义失败率阈值、最小统计调用量、打开态等待时间和半开态探测量。
     */
    public static class CircuitBreaker {
        /** 失败率阈值，超过后进入打开态。 */
        private float failureRateThreshold = 50.0f;
        /** 触发失败率计算所需的最小调用次数。 */
        private int minNumberOfCalls = 10;
        /** 打开态等待时间，单位毫秒。 */
        private long waitDurationInOpenStateMillis = 30000L;
        /** 半开态允许的探测调用数。 */
        private int permittedHalfOpenCalls = 5;
        /** @return 失败率阈值 */
        public float getFailureRateThreshold() { return failureRateThreshold; }
        /** @param failureRateThreshold 失败率阈值 */
        public void setFailureRateThreshold(float failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }
        /** @return 触发失败率计算所需的最小调用次数 */
        public int getMinNumberOfCalls() { return minNumberOfCalls; }
        /** @param minNumberOfCalls 触发失败率计算所需的最小调用次数 */
        public void setMinNumberOfCalls(int minNumberOfCalls) { this.minNumberOfCalls = minNumberOfCalls; }
        /** @return 打开态等待时间，单位毫秒 */
        public long getWaitDurationInOpenStateMillis() { return waitDurationInOpenStateMillis; }
        /** @param waitDurationInOpenStateMillis 打开态等待时间，单位毫秒 */
        public void setWaitDurationInOpenStateMillis(long waitDurationInOpenStateMillis) { this.waitDurationInOpenStateMillis = waitDurationInOpenStateMillis; }
        /** @return 半开态允许的探测调用数 */
        public int getPermittedHalfOpenCalls() { return permittedHalfOpenCalls; }
        /** @param permittedHalfOpenCalls 半开态允许的探测调用数 */
        public void setPermittedHalfOpenCalls(int permittedHalfOpenCalls) { this.permittedHalfOpenCalls = permittedHalfOpenCalls; }
    }

    /**
     * 过滤器链配置组。
     *
     * 主要职责：允许应用按阶段配置过滤器启用列表和自定义顺序，支撑日志、指标、熔断、限流等横切能力。
     */
    public static class Filter {
        /** Consumer 阶段过滤器名称列表，通常用于服务级处理。 */
        private List<String> consumer = new ArrayList<>();
        /** Invoker 阶段过滤器名称列表，通常用于实例级处理。 */
        private List<String> invoker = new ArrayList<>();
        /** Provider 阶段过滤器名称列表，通常用于服务端入站处理。 */
        private List<String> provider = new ArrayList<>();
        /** 过滤器排序配置，key 为过滤器名称，value 为顺序值。 */
        private Map<String, Integer> order = new HashMap<>();
        /** @return Consumer 阶段过滤器名称列表 */
        public List<String> getConsumer() { return consumer; }
        /** @param consumer Consumer 阶段过滤器名称列表 */
        public void setConsumer(List<String> consumer) { this.consumer = consumer; }
        /** @return Invoker 阶段过滤器名称列表 */
        public List<String> getInvoker() { return invoker; }
        /** @param invoker Invoker 阶段过滤器名称列表 */
        public void setInvoker(List<String> invoker) { this.invoker = invoker; }
        /** @return Provider 阶段过滤器名称列表 */
        public List<String> getProvider() { return provider; }
        /** @param provider Provider 阶段过滤器名称列表 */
        public void setProvider(List<String> provider) { this.provider = provider; }
        /** @return 过滤器排序配置 */
        public Map<String, Integer> getOrder() { return order; }
        /** @param order 过滤器排序配置 */
        public void setOrder(Map<String, Integer> order) { this.order = order; }
    }
}

