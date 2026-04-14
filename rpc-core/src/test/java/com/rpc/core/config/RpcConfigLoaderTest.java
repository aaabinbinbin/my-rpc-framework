package com.rpc.core.config;

import com.rpc.core.config.client.RpcClientConfigBinder;
import com.rpc.core.config.filter.RpcFilterConfigBinder;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.config.method.RpcMethodConfigBinder;
import com.rpc.core.config.server.RpcServerConfigBinder;
import com.rpc.core.config.source.RpcPropertySource;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.MethodConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：RPC配置加载器测试")
class RpcConfigLoaderTest {
    @DisplayName("验证解析方法配置来自属性场景")
    @Test
    void shouldParseMethodConfigsFromProperties() {
        Properties properties = new Properties();
        properties.setProperty("rpc.server.scanPackages", "com.rpc.provider, com.rpc.demo");
        properties.setProperty("rpc.server.autoRegisterAnnotatedServices", "false");
        properties.setProperty("rpc.server.rateLimit.enabled", "true");
        properties.setProperty("rpc.server.rateLimit.permitsPerSecond", "7");
        properties.setProperty("rpc.server.degradation.enabled", "true");
        properties.setProperty("rpc.filter.consumer", "trace,mdc");
        properties.setProperty("rpc.filter.invoker", "consumerCircuitBreaker");
        properties.setProperty("rpc.filter.provider", "providerMdc,providerMetrics");
        properties.setProperty("rpc.filter.order.providerMetrics", "99");
        properties.setProperty("rpc.client.methods", "fastHello");
        properties.setProperty("rpc.client.method.fastHello.service", "com.rpc.HelloService");
        properties.setProperty("rpc.client.method.fastHello.method", "sayHello");
        properties.setProperty("rpc.client.method.fastHello.retryTimes", "0");
        properties.setProperty("rpc.client.method.fastHello.cluster", "failfast");
        properties.setProperty("rpc.client.method.fastHello.readTimeout", "500");
        properties.setProperty("rpc.client.method.fastHello.serializer", "json");
        properties.setProperty("rpc.client.method.fastHello.loadBalancer", "roundRobin");
        properties.setProperty("rpc.client.method.fastHello.rateLimitEnabled", "true");
        properties.setProperty("rpc.client.method.fastHello.rateLimitPermitsPerSecond", "9");
        properties.setProperty("rpc.client.method.fastHello.circuitBreakerScope", "method");
        properties.setProperty("rpc.client.rateLimit.enabled", "true");
        properties.setProperty("rpc.client.rateLimit.permitsPerSecond", "42");
        properties.setProperty("rpc.client.maxInflightRequestsPerConnection", "33");
        properties.setProperty("rpc.client.maxConnectionsPerAddress", "4");
        properties.setProperty("rpc.client.maxTotalConnections", "40");
        properties.setProperty("rpc.client.maxPendingRequests", "500");
        properties.setProperty("rpc.client.idleConnectionTtlMillis", "60000");
        properties.setProperty("rpc.client.idleConnectionEvictIntervalMillis", "15000");
        properties.setProperty("rpc.client.circuitBreaker.failureRateThreshold", "66.5");
        properties.setProperty("rpc.client.circuitBreaker.minNumberOfCalls", "12");
        properties.setProperty("rpc.client.circuitBreaker.waitDurationInOpenStateMillis", "12345");
        properties.setProperty("rpc.client.circuitBreaker.permittedHalfOpenCalls", "3");

        RpcPropertySource propertySource = new RpcPropertySource(properties);
        RpcMethodConfigBinder methodConfigBinder = new RpcMethodConfigBinder();
        RpcFilterConfigBinder filterConfigBinder = new RpcFilterConfigBinder();
        RpcServerConfigBinder serverConfigBinder = new RpcServerConfigBinder();
        RpcClientConfigBinder clientConfigBinder = new RpcClientConfigBinder();

        List<MethodConfig> configs = methodConfigBinder.bind(propertySource);

        assertEquals(1, configs.size());
        MethodConfig config = configs.get(0);
        assertEquals("com.rpc.HelloService", config.getServiceName());
        assertEquals("sayHello", config.getMethodName());
        assertEquals(0, config.getRetryTimes());
        assertEquals(ClusterStrategy.FAIL_FAST, config.getClusterStrategy());
        assertEquals(500, config.getReadTimeout());
        assertEquals("json", config.getSerializerName());
        assertEquals("roundRobin", config.getLoadBalancerName());
        assertEquals(true, config.getRateLimitEnabled());
        assertEquals(9, config.getRateLimitPermitsPerSecond());
        assertEquals(com.rpc.core.invoke.invocation.CircuitBreakerScope.METHOD, config.getCircuitBreakerScope());

        List<String> scanPackages = propertySource.getList("rpc.server.scanPackages", List.of());
        assertEquals(List.of("com.rpc.provider", "com.rpc.demo"), scanPackages);
        List<String> consumerFilters = propertySource.getList("rpc.filter.consumer", List.of());
        assertEquals(List.of("trace", "mdc"), consumerFilters);
        List<String> providerFilters = propertySource.getList("rpc.filter.provider", List.of());
        assertEquals(List.of("providerMdc", "providerMetrics"), providerFilters);

        assertEquals(false, propertySource.getBoolean("rpc.server.autoRegisterAnnotatedServices", true));
        assertEquals(true, propertySource.getBoolean("rpc.server.rateLimit.enabled", false));
        assertEquals(true, propertySource.getBoolean("rpc.server.degradation.enabled", false));
        assertEquals(true, propertySource.getBoolean("rpc.client.rateLimit.enabled", false));
        assertEquals(7, propertySource.getInt("rpc.server.rateLimit.permitsPerSecond", 0));
        assertEquals(42, propertySource.getInt("rpc.client.rateLimit.permitsPerSecond", 0));
        assertEquals(12, propertySource.getInt("rpc.client.circuitBreaker.minNumberOfCalls", 0));
        assertEquals(3, propertySource.getInt("rpc.client.circuitBreaker.permittedHalfOpenCalls", 0));
        assertEquals(12345L, propertySource.getLong("rpc.client.circuitBreaker.waitDurationInOpenStateMillis", 0L));
        assertEquals("66.5", propertySource.get("rpc.client.circuitBreaker.failureRateThreshold", "0"));

        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        serverConfigBinder.bind(propertySource, frameworkConfig);
        clientConfigBinder.bind(propertySource, frameworkConfig);
        filterConfigBinder.bind(propertySource, frameworkConfig);

        assertEquals(99, frameworkConfig.getFilterOrders().get("providerMetrics"));
        assertEquals(true, frameworkConfig.isServerRateLimitEnabled());
        assertEquals(true, frameworkConfig.isRateLimitEnabled());
        assertEquals(42, frameworkConfig.getRateLimitPermitsPerSecond());
        assertEquals(33, frameworkConfig.getMaxInflightRequestsPerConnection());
        assertEquals(4, frameworkConfig.getMaxConnectionsPerAddress());
        assertEquals(40, frameworkConfig.getMaxTotalConnections());
        assertEquals(500, frameworkConfig.getMaxPendingRequests());
        assertEquals(60000L, frameworkConfig.getIdleConnectionTtlMillis());
        assertEquals(15000L, frameworkConfig.getIdleConnectionEvictIntervalMillis());
    }
}
