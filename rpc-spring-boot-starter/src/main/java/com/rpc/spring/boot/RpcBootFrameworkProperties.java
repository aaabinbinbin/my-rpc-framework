package com.rpc.spring.boot;

import com.rpc.core.config.RpcFrameworkConfig;
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

@ConfigurationProperties(prefix = "rpc")
public class RpcBootFrameworkProperties {
    private String transport = "netty";
    private String serializer = "protobuf";
    private String loadbalancer = "random";
    private Registry registry = new Registry();
    private Server server = new Server();
    private Client client = new Client();
    private Filter filter = new Filter();

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
        config.setDegradationFailureThreshold(client.degradationFailureThreshold);
        config.setRateLimitEnabled(client.rateLimit.enabled);
        config.setRateLimitPermitsPerSecond(client.rateLimit.permitsPerSecond);
        config.setCircuitBreakerFailureRateThreshold(client.circuitBreaker.failureRateThreshold);
        config.setCircuitBreakerMinNumberOfCalls(client.circuitBreaker.minNumberOfCalls);
        config.setCircuitBreakerWaitDurationInOpenStateMillis(client.circuitBreaker.waitDurationInOpenStateMillis);
        config.setCircuitBreakerPermittedHalfOpenCalls(client.circuitBreaker.permittedHalfOpenCalls);
        return config;
    }

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getSerializer() { return serializer; }
    public void setSerializer(String serializer) { this.serializer = serializer; }
    public String getLoadbalancer() { return loadbalancer; }
    public void setLoadbalancer(String loadbalancer) { this.loadbalancer = loadbalancer; }
    public Registry getRegistry() { return registry; }
    public void setRegistry(Registry registry) { this.registry = registry; }
    public Server getServer() { return server; }
    public void setServer(Server server) { this.server = server; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
    public Filter getFilter() { return filter; }
    public void setFilter(Filter filter) { this.filter = filter; }

    public static class Registry {
        private String type = "zookeeper";
        private String address = "127.0.0.1:2181";
        private int timeout = 5000;
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
    }

    public static class Server {
        private String host = "127.0.0.1";
        private int port = 8080;
        private List<String> scanPackages = new ArrayList<>();
        private boolean autoRegisterAnnotatedServices = true;
        private int bossThreads = 1;
        private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;
        private Biz biz = new Biz();
        private RateLimit rateLimit = new RateLimit();
        private Degradation degradation = new Degradation();
        private int shutdownTimeout = 10;
        private int readerIdleTime = 30000;
        private int writerIdleTime = 0;
        private int allIdleTime = 0;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public List<String> getScanPackages() { return scanPackages; }
        public void setScanPackages(List<String> scanPackages) { this.scanPackages = scanPackages; }
        public boolean isAutoRegisterAnnotatedServices() { return autoRegisterAnnotatedServices; }
        public void setAutoRegisterAnnotatedServices(boolean autoRegisterAnnotatedServices) { this.autoRegisterAnnotatedServices = autoRegisterAnnotatedServices; }
        public int getBossThreads() { return bossThreads; }
        public void setBossThreads(int bossThreads) { this.bossThreads = bossThreads; }
        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
        public Biz getBiz() { return biz; }
        public void setBiz(Biz biz) { this.biz = biz; }
        public RateLimit getRateLimit() { return rateLimit; }
        public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
        public Degradation getDegradation() { return degradation; }
        public void setDegradation(Degradation degradation) { this.degradation = degradation; }
        public int getShutdownTimeout() { return shutdownTimeout; }
        public void setShutdownTimeout(int shutdownTimeout) { this.shutdownTimeout = shutdownTimeout; }
        public int getReaderIdleTime() { return readerIdleTime; }
        public void setReaderIdleTime(int readerIdleTime) { this.readerIdleTime = readerIdleTime; }
        public int getWriterIdleTime() { return writerIdleTime; }
        public void setWriterIdleTime(int writerIdleTime) { this.writerIdleTime = writerIdleTime; }
        public int getAllIdleTime() { return allIdleTime; }
        public void setAllIdleTime(int allIdleTime) { this.allIdleTime = allIdleTime; }
    }

    public static class Biz {
        private int coreThreads = Runtime.getRuntime().availableProcessors();
        private int maxThreads = Runtime.getRuntime().availableProcessors() * 2;
        private int queueCapacity = 1000;
        public int getCoreThreads() { return coreThreads; }
        public void setCoreThreads(int coreThreads) { this.coreThreads = coreThreads; }
        public int getMaxThreads() { return maxThreads; }
        public void setMaxThreads(int maxThreads) { this.maxThreads = maxThreads; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    public static class RateLimit {
        private boolean enabled;
        private int permitsPerSecond = 100;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPermitsPerSecond() { return permitsPerSecond; }
        public void setPermitsPerSecond(int permitsPerSecond) { this.permitsPerSecond = permitsPerSecond; }
    }

    public static class Degradation {
        private boolean enabled;
        private String policy = "failFast";
        private Map<String, String> defaultValue = new HashMap<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPolicy() { return policy; }
        public void setPolicy(String policy) { this.policy = policy; }
        public Map<String, String> getDefaultValue() { return defaultValue; }
        public void setDefaultValue(Map<String, String> defaultValue) { this.defaultValue = defaultValue; }
    }

    public static class Client {
        private int connectTimeout = 5000;
        private int readTimeout = 10000;
        private int heartbeatInterval = 30000;
        private int writerIdleTime = 30000;
        private int readerIdleTime = 10000;
        private int retryTimes = 3;
        private String cluster = "failover";
        private List<ClientMethod> methods = new ArrayList<>();
        private Reconnect reconnect = new Reconnect();
        private Discovery discovery = new Discovery();
        private boolean enableDegradation;
        private Degradation degradation = new Degradation();
        private int degradationFailureThreshold = 10;
        private RateLimit rateLimit = new RateLimit();
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
        public int getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }
        public int getReadTimeout() { return readTimeout; }
        public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }
        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        public int getWriterIdleTime() { return writerIdleTime; }
        public void setWriterIdleTime(int writerIdleTime) { this.writerIdleTime = writerIdleTime; }
        public int getReaderIdleTime() { return readerIdleTime; }
        public void setReaderIdleTime(int readerIdleTime) { this.readerIdleTime = readerIdleTime; }
        public int getRetryTimes() { return retryTimes; }
        public void setRetryTimes(int retryTimes) { this.retryTimes = retryTimes; }
        public String getCluster() { return cluster; }
        public void setCluster(String cluster) { this.cluster = cluster; }
        public List<ClientMethod> getMethods() { return methods; }
        public void setMethods(List<ClientMethod> methods) { this.methods = methods; }
        public Reconnect getReconnect() { return reconnect; }
        public void setReconnect(Reconnect reconnect) { this.reconnect = reconnect; }
        public Discovery getDiscovery() { return discovery; }
        public void setDiscovery(Discovery discovery) { this.discovery = discovery; }
        public boolean isEnableDegradation() { return enableDegradation; }
        public void setEnableDegradation(boolean enableDegradation) { this.enableDegradation = enableDegradation; }
        public Degradation getDegradation() { return degradation; }
        public void setDegradation(Degradation degradation) { this.degradation = degradation; }
        public int getDegradationFailureThreshold() { return degradationFailureThreshold; }
        public void setDegradationFailureThreshold(int degradationFailureThreshold) { this.degradationFailureThreshold = degradationFailureThreshold; }
        public RateLimit getRateLimit() { return rateLimit; }
        public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
        public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
        public void setCircuitBreaker(CircuitBreaker circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    }

    public static class ClientMethod {
        private String serviceName;
        private String methodName;
        private Integer retryTimes;
        private String clusterStrategy;
        private Integer readTimeout;
        private String serializerName;
        private String loadBalancerName;
        private Boolean rateLimitEnabled;
        private Integer rateLimitPermitsPerSecond;
        private String circuitBreakerScope;

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

        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public Integer getRetryTimes() { return retryTimes; }
        public void setRetryTimes(Integer retryTimes) { this.retryTimes = retryTimes; }
        public String getClusterStrategy() { return clusterStrategy; }
        public void setClusterStrategy(String clusterStrategy) { this.clusterStrategy = clusterStrategy; }
        public Integer getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Integer readTimeout) { this.readTimeout = readTimeout; }
        public String getSerializerName() { return serializerName; }
        public void setSerializerName(String serializerName) { this.serializerName = serializerName; }
        public String getLoadBalancerName() { return loadBalancerName; }
        public void setLoadBalancerName(String loadBalancerName) { this.loadBalancerName = loadBalancerName; }
        public Boolean getRateLimitEnabled() { return rateLimitEnabled; }
        public void setRateLimitEnabled(Boolean rateLimitEnabled) { this.rateLimitEnabled = rateLimitEnabled; }
        public Integer getRateLimitPermitsPerSecond() { return rateLimitPermitsPerSecond; }
        public void setRateLimitPermitsPerSecond(Integer rateLimitPermitsPerSecond) { this.rateLimitPermitsPerSecond = rateLimitPermitsPerSecond; }
        public String getCircuitBreakerScope() { return circuitBreakerScope; }
        public void setCircuitBreakerScope(String circuitBreakerScope) { this.circuitBreakerScope = circuitBreakerScope; }
    }

    public static class Reconnect {
        private boolean enabled = true;
        private int maxRetryTimes = 5;
        private int initialDelaySeconds = 2;
        private int maxDelaySeconds = 60;
        private Jitter jitter = new Jitter();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxRetryTimes() { return maxRetryTimes; }
        public void setMaxRetryTimes(int maxRetryTimes) { this.maxRetryTimes = maxRetryTimes; }
        public int getInitialDelaySeconds() { return initialDelaySeconds; }
        public void setInitialDelaySeconds(int initialDelaySeconds) { this.initialDelaySeconds = initialDelaySeconds; }
        public int getMaxDelaySeconds() { return maxDelaySeconds; }
        public void setMaxDelaySeconds(int maxDelaySeconds) { this.maxDelaySeconds = maxDelaySeconds; }
        public Jitter getJitter() { return jitter; }
        public void setJitter(Jitter jitter) { this.jitter = jitter; }
    }

    public static class Jitter {
        private boolean enabled = true;
        private int minSeconds = 0;
        private int maxSeconds = 1;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMinSeconds() { return minSeconds; }
        public void setMinSeconds(int minSeconds) { this.minSeconds = minSeconds; }
        public int getMaxSeconds() { return maxSeconds; }
        public void setMaxSeconds(int maxSeconds) { this.maxSeconds = maxSeconds; }
    }

    public static class Discovery {
        private Preheat preheat = new Preheat();
        private long cacheTtlMillis = 30000L;
        private boolean allowStaleOnFailure = true;
        public Preheat getPreheat() { return preheat; }
        public void setPreheat(Preheat preheat) { this.preheat = preheat; }
        public long getCacheTtlMillis() { return cacheTtlMillis; }
        public void setCacheTtlMillis(long cacheTtlMillis) { this.cacheTtlMillis = cacheTtlMillis; }
        public boolean isAllowStaleOnFailure() { return allowStaleOnFailure; }
        public void setAllowStaleOnFailure(boolean allowStaleOnFailure) { this.allowStaleOnFailure = allowStaleOnFailure; }
    }

    public static class Preheat {
        private boolean enabled;
        private List<String> services = new ArrayList<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getServices() { return services; }
        public void setServices(List<String> services) { this.services = services; }
    }

    public static class CircuitBreaker {
        private float failureRateThreshold = 50.0f;
        private int minNumberOfCalls = 10;
        private long waitDurationInOpenStateMillis = 30000L;
        private int permittedHalfOpenCalls = 5;
        public float getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(float failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }
        public int getMinNumberOfCalls() { return minNumberOfCalls; }
        public void setMinNumberOfCalls(int minNumberOfCalls) { this.minNumberOfCalls = minNumberOfCalls; }
        public long getWaitDurationInOpenStateMillis() { return waitDurationInOpenStateMillis; }
        public void setWaitDurationInOpenStateMillis(long waitDurationInOpenStateMillis) { this.waitDurationInOpenStateMillis = waitDurationInOpenStateMillis; }
        public int getPermittedHalfOpenCalls() { return permittedHalfOpenCalls; }
        public void setPermittedHalfOpenCalls(int permittedHalfOpenCalls) { this.permittedHalfOpenCalls = permittedHalfOpenCalls; }
    }

    public static class Filter {
        private List<String> consumer = new ArrayList<>();
        private List<String> invoker = new ArrayList<>();
        private List<String> provider = new ArrayList<>();
        private Map<String, Integer> order = new HashMap<>();
        public List<String> getConsumer() { return consumer; }
        public void setConsumer(List<String> consumer) { this.consumer = consumer; }
        public List<String> getInvoker() { return invoker; }
        public void setInvoker(List<String> invoker) { this.invoker = invoker; }
        public List<String> getProvider() { return provider; }
        public void setProvider(List<String> provider) { this.provider = provider; }
        public Map<String, Integer> getOrder() { return order; }
        public void setOrder(Map<String, Integer> order) { this.order = order; }
    }
}

