package com.rpc.core.config;

import com.rpc.core.config.client.RpcClientConfigBinder;
import com.rpc.core.config.filter.RpcFilterConfigBinder;
import com.rpc.core.config.framework.RpcConfigKeys;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.config.server.RpcServerConfigBinder;
import com.rpc.core.config.source.RpcPropertySource;
import com.rpc.core.invoke.invocation.CircuitBreakerScope;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.MethodConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("测试类：配置绑定器默认值和边界测试")
class RpcConfigBinderTest {
    @DisplayName("验证客户端配置缺省时保留框架默认值")
    @Test
    void shouldKeepClientDefaultsWhenPropertiesMissing() {
        RpcFrameworkConfig config = new RpcFrameworkConfig();
        int defaultConnectTimeout = config.getConnectTimeout();
        boolean defaultReconnectEnabled = config.isReconnectEnabled();

        new RpcClientConfigBinder().bind(new RpcPropertySource(new Properties()), config);

        assertEquals(defaultConnectTimeout, config.getConnectTimeout());
        assertEquals(defaultReconnectEnabled, config.isReconnectEnabled());
        assertEquals(ClusterStrategy.FAIL_OVER, config.getClusterStrategy());
        assertTrue(config.getMethodConfigs().isEmpty());
    }

    @DisplayName("验证客户端配置会绑定连接、重试、服务发现、限流和熔断参数")
    @Test
    void shouldBindClientRuntimeAndResilienceProperties() {
        Properties properties = new Properties();
        properties.setProperty(RpcConfigKeys.CLIENT_CONNECT_TIMEOUT, "111");
        properties.setProperty(RpcConfigKeys.CLIENT_READ_TIMEOUT, "222");
        properties.setProperty(RpcConfigKeys.CLIENT_RETRY_TIMES, "4");
        properties.setProperty(RpcConfigKeys.CLIENT_CLUSTER, "fail-fast");
        properties.setProperty(RpcConfigKeys.CLIENT_RECONNECT_ENABLED, "false");
        properties.setProperty(RpcConfigKeys.CLIENT_DISCOVERY_PREHEAT_ENABLED, "true");
        properties.setProperty(RpcConfigKeys.CLIENT_DISCOVERY_PREHEAT_SERVICES, "svcA, svcB, ,svcC");
        properties.setProperty(RpcConfigKeys.CLIENT_RATE_LIMIT_ENABLED, "true");
        properties.setProperty(RpcConfigKeys.CLIENT_RATE_LIMIT_PERMITS_PER_SECOND, "88");
        properties.setProperty(RpcConfigKeys.CLIENT_CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD, "33.5");
        properties.setProperty(RpcConfigKeys.CLIENT_CIRCUIT_BREAKER_MIN_NUMBER_OF_CALLS, "6");

        RpcFrameworkConfig config = new RpcFrameworkConfig();
        new RpcClientConfigBinder().bind(new RpcPropertySource(properties), config);

        assertEquals(111, config.getConnectTimeout());
        assertEquals(222, config.getReadTimeout());
        assertEquals(4, config.getRetryTimes());
        assertEquals(ClusterStrategy.FAIL_FAST, config.getClusterStrategy());
        assertFalse(config.isReconnectEnabled());
        assertTrue(config.isDiscoveryPreheatEnabled());
        assertEquals(List.of("svcA", "svcB", "svcC"), config.getDiscoveryPreheatServices());
        assertTrue(config.isRateLimitEnabled());
        assertEquals(88, config.getRateLimitPermitsPerSecond());
        assertEquals(33.5f, config.getCircuitBreakerFailureRateThreshold());
        assertEquals(6, config.getCircuitBreakerMinNumberOfCalls());
    }

    @DisplayName("验证方法级配置会过滤缺少服务或方法名的无效条目")
    @Test
    void shouldBindOnlyValidMethodLevelOverrides() {
        Properties properties = new Properties();
        properties.setProperty(RpcConfigKeys.CLIENT_METHODS, "valid,missingService,missingMethod");
        properties.setProperty(RpcConfigKeys.CLIENT_METHOD_PREFIX + "valid.service", "svc");
        properties.setProperty(RpcConfigKeys.CLIENT_METHOD_PREFIX + "valid.method", "hello");
        properties.setProperty(RpcConfigKeys.CLIENT_METHOD_PREFIX + "valid.retryTimes", "2");
        properties.setProperty(RpcConfigKeys.CLIENT_METHOD_PREFIX + "valid.cluster", "failfast");
        properties.setProperty(RpcConfigKeys.CLIENT_METHOD_PREFIX + "valid.circuitBreakerScope", "method");
        properties.setProperty(RpcConfigKeys.CLIENT_METHOD_PREFIX + "missingService.method", "hello");
        properties.setProperty(RpcConfigKeys.CLIENT_METHOD_PREFIX + "missingMethod.service", "svc");

        RpcFrameworkConfig config = new RpcFrameworkConfig();
        new RpcClientConfigBinder().bind(new RpcPropertySource(properties), config);

        assertEquals(1, config.getMethodConfigs().size());
        MethodConfig methodConfig = config.getMethodConfigs().get(0);
        assertEquals("svc", methodConfig.getServiceName());
        assertEquals("hello", methodConfig.getMethodName());
        assertEquals(2, methodConfig.getRetryTimes());
        assertEquals(ClusterStrategy.FAIL_FAST, methodConfig.getClusterStrategy());
        assertEquals(CircuitBreakerScope.METHOD, methodConfig.getCircuitBreakerScope());
    }

    @DisplayName("验证服务端配置会绑定线程池、限流、降级和空闲检测参数")
    @Test
    void shouldBindServerRuntimeProperties() {
        Properties properties = new Properties();
        properties.setProperty(RpcConfigKeys.SERVER_HOST, "0.0.0.0");
        properties.setProperty(RpcConfigKeys.SERVER_PORT, "19090");
        properties.setProperty(RpcConfigKeys.SERVER_SCAN_PACKAGES, "com.a, com.b");
        properties.setProperty(RpcConfigKeys.SERVER_BIZ_CORE_THREADS, "3");
        properties.setProperty(RpcConfigKeys.SERVER_BIZ_MAX_THREADS, "9");
        properties.setProperty(RpcConfigKeys.SERVER_RATE_LIMIT_ENABLED, "true");
        properties.setProperty(RpcConfigKeys.SERVER_DEGRADATION_ENABLED, "true");
        properties.setProperty(RpcConfigKeys.SERVER_DEGRADATION_DEFAULT_VALUE_PREFIX + "svc#m", "fallback");
        properties.setProperty(RpcConfigKeys.SERVER_READER_IDLE, "1234");

        RpcFrameworkConfig config = new RpcFrameworkConfig();
        new RpcServerConfigBinder().bind(new RpcPropertySource(properties), config);

        assertEquals("0.0.0.0", config.getServerHost());
        assertEquals(19090, config.getServerPort());
        assertEquals(List.of("com.a", "com.b"), config.getServerScanPackages());
        assertEquals(3, config.getBizCoreThreads());
        assertEquals(9, config.getBizMaxThreads());
        assertTrue(config.isServerRateLimitEnabled());
        assertTrue(config.isServerDegradationEnabled());
        assertEquals("fallback", config.getServerDegradationDefaultValues().get("svc#m"));
        assertEquals(1234, config.getServerReaderIdleTime());
    }

    @DisplayName("验证过滤器配置会解析三段过滤器链和顺序覆盖")
    @Test
    void shouldBindFilterNamesAndOrderOverrides() {
        Properties properties = new Properties();
        properties.setProperty(RpcConfigKeys.FILTER_CONSUMER, "trace, mdc");
        properties.setProperty(RpcConfigKeys.FILTER_INVOKER, "consumerCircuitBreaker");
        properties.setProperty(RpcConfigKeys.FILTER_PROVIDER, "providerMetrics, providerRateLimit");
        properties.setProperty(RpcConfigKeys.FILTER_ORDER_PREFIX + "trace", "-100");
        properties.setProperty(RpcConfigKeys.FILTER_ORDER_PREFIX, "999");

        RpcFrameworkConfig config = new RpcFrameworkConfig();
        new RpcFilterConfigBinder().bind(new RpcPropertySource(properties), config);

        assertEquals(List.of("trace", "mdc"), config.getConsumerFilters());
        assertEquals(List.of("consumerCircuitBreaker"), config.getInvokerFilters());
        assertEquals(List.of("providerMetrics", "providerRateLimit"), config.getProviderFilters());
        assertEquals(-100, config.getFilterOrders().get("trace"));
        assertFalse(config.getFilterOrders().containsKey(""));
    }

    @DisplayName("验证非法数字配置会在绑定阶段暴露异常")
    @Test
    void shouldFailFastWhenNumericPropertyInvalid() {
        Properties properties = new Properties();
        properties.setProperty(RpcConfigKeys.CLIENT_CONNECT_TIMEOUT, "not-number");

        assertThrows(NumberFormatException.class,
                () -> new RpcClientConfigBinder().bind(new RpcPropertySource(properties), new RpcFrameworkConfig()));
    }
}
