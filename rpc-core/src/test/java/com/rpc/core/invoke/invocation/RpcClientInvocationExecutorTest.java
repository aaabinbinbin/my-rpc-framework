package com.rpc.core.invoke.invocation;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.resilience.CircuitBreakerState;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import com.rpc.core.resilience.degrade.FailFastDegradation;
import com.rpc.core.invoke.filter.runtime.FilterManager;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfigurator;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfig;
import com.rpc.core.resilience.ratelimit.RateLimiterManager;
import com.rpc.core.resilience.retry.DefaultRetryStrategy;
import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.transport.netty.client.invocation.RpcClientInvocationExecutor;
import com.rpc.core.transport.netty.client.invocation.RpcServiceResolver;
import com.rpc.core.transport.netty.client.invocation.RpcTransportInvoker;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：RPC客户端调用执行器测试")
class RpcClientInvocationExecutorTest {
    @DisplayName("验证写入方法Level覆盖到请求附件场景")
    @Test
    void shouldWriteMethodLevelOverridesIntoRequestAttachments() throws Exception {
        CircuitBreakerManager.getInstance().clear();
        FilterRuntimeConfig.configureConsumerDegradation(false, null);
        InvocationOptionsResolver resolver = request -> InvocationOptions.builder()
                .retryTimes(0)
                .clusterStrategy(ClusterStrategy.FAIL_FAST)
                .readTimeout(1234)
                .serializerName("json")
                .loadBalancerName("roundRobin")
                .build();
        RpcClientInvocationExecutor executor = new RpcClientInvocationExecutor(
                stubResolver(),
                CircuitBreakerManager.getInstance(),
                new RetryExecutor(new DefaultRetryStrategy(), 0),
                resolver,
                new RateLimiterManager()
        );
        RpcRequest request = RpcRequest.builder()
                .serviceName("svc")
                .methodName("m")
                .build();

        RpcResponse response = executor.execute(request, successInvoker());

        assertEquals(200, response.getCode());
        assertEquals("1234", request.getAttachments().get(InvocationAttachmentKeys.READ_TIMEOUT));
        assertEquals("json", request.getAttachments().get(InvocationAttachmentKeys.SERIALIZER));
        assertEquals("roundRobin", request.getAttachments().get(InvocationAttachmentKeys.LOAD_BALANCER));
    }

    @DisplayName("验证写入请求ID在MDC在传输尝试场景")
    @Test
    void shouldPopulateRequestIdInMdcDuringTransportAttempt() throws Exception {
        CircuitBreakerManager.getInstance().clear();
        FilterRuntimeConfig.configureConsumerDegradation(false, null);
        RpcClientInvocationExecutor executor = new RpcClientInvocationExecutor(
                stubResolver(),
                CircuitBreakerManager.getInstance(),
                new RetryExecutor(new DefaultRetryStrategy(), 0),
                request -> InvocationOptions.builder()
                        .retryTimes(0)
                        .clusterStrategy(ClusterStrategy.FAIL_FAST)
                        .rateLimitEnabled(false)
                        .rateLimitPermitsPerSecond(100)
                        .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                        .build(),
                new RateLimiterManager()
        );
        RpcRequest request = RpcRequest.builder()
                .serviceName("svc")
                .methodName("m")
                .build();

        RpcResponse response = executor.execute(request, (rpcRequest, address) -> {
            assertNotNull(rpcRequest.getRequestId());
            assertEquals(rpcRequest.getRequestId(), MDC.get("rpcRequestId"));
            return RpcResponse.success("ok", rpcRequest.getRequestId());
        });

        assertEquals(200, response.getCode());
        assertNull(MDC.get("rpcRequestId"));
    }

    @DisplayName("验证返回限流限制超限当限流器拒绝场景")
    @Test
    void shouldReturnRateLimitExceededWhenLimiterRejects() throws Exception {
        CircuitBreakerManager.getInstance().clear();
        FilterRuntimeConfig.configureConsumerDegradation(false, null);
        RateLimiterManager rateLimiterManager = new RateLimiterManager();
        rateLimiterManager.configure(true, 1);
        RpcClientInvocationExecutor executor = new RpcClientInvocationExecutor(
                stubResolver(),
                CircuitBreakerManager.getInstance(),
                new RetryExecutor(new DefaultRetryStrategy(), 0),
                request -> InvocationOptions.builder()
                        .retryTimes(0)
                        .clusterStrategy(ClusterStrategy.FAIL_FAST)
                        .rateLimitEnabled(true)
                        .rateLimitPermitsPerSecond(1)
                        .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                        .build(),
                rateLimiterManager
        );

        RpcRequest first = RpcRequest.builder().requestId("1").serviceName("svc").methodName("m").build();
        RpcRequest second = RpcRequest.builder().requestId("2").serviceName("svc").methodName("m").build();

        assertEquals(200, executor.execute(first, successInvoker()).getCode());
        RpcResponse limited = executor.execute(second, successInvoker());
        assertEquals(ErrorCode.RATE_LIMIT_EXCEEDED.getCode(), limited.getCode());
    }

    @DisplayName("验证应用降级当熔断器Is打开场景")
    @Test
    void shouldApplyDegradationWhenCircuitBreakerIsOpen() throws Exception {
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        circuitBreakerManager.clear();
        circuitBreakerManager.configure(50.0f, 1, 60000L, 1);
        circuitBreakerManager.getServiceCircuitBreaker("svc").recordFailure();
        FilterRuntimeConfig.configureConsumerDegradation(true, new FailFastDegradation());

        RpcClientInvocationExecutor executor = new RpcClientInvocationExecutor(
                stubResolver(),
                circuitBreakerManager,
                new RetryExecutor(new DefaultRetryStrategy(), 0),
                request -> InvocationOptions.builder()
                        .retryTimes(0)
                        .clusterStrategy(ClusterStrategy.FAIL_FAST)
                        .rateLimitEnabled(false)
                        .rateLimitPermitsPerSecond(100)
                        .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                        .build(),
                new RateLimiterManager()
        );

        RpcResponse degraded = executor.execute(
                RpcRequest.builder().requestId("3").serviceName("svc").methodName("m").build(),
                successInvoker()
        );

        assertEquals(ErrorCode.SERVICE_DEGRADED.getCode(), degraded.getCode());
    }

    @DisplayName("验证隔离熔断器按方法当粒度Is方法场景")
    @Test
    void shouldIsolateCircuitBreakerByMethodWhenScopeIsMethod() throws Exception {
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        circuitBreakerManager.clear();
        circuitBreakerManager.configure(50.0f, 1, 60000L, 1);
        FilterRuntimeConfig.configureConsumerDegradation(true, new FailFastDegradation());

        InvocationOptionsResolver resolver = request -> InvocationOptions.builder()
                .retryTimes(0)
                .clusterStrategy(ClusterStrategy.FAIL_FAST)
                .rateLimitEnabled(false)
                .rateLimitPermitsPerSecond(100)
                .circuitBreakerScope(com.rpc.core.invoke.invocation.CircuitBreakerScope.METHOD)
                .build();

        RpcClientInvocationExecutor executor = new RpcClientInvocationExecutor(
                stubResolver(),
                circuitBreakerManager,
                new RetryExecutor(new DefaultRetryStrategy(), 0),
                resolver,
                new RateLimiterManager()
        );

        RpcRequest failingRequest = RpcRequest.builder().requestId("4").serviceName("svc").methodName("fast").build();
        RpcRequest otherMethodRequest = RpcRequest.builder().requestId("5").serviceName("svc").methodName("slow").build();

        assertThrows(RpcException.class, () -> executor.execute(failingRequest, (request, address) -> {
            throw new RpcException(ErrorCode.SERVER_ERROR, "boom");
        }));
        RpcResponse degraded = executor.execute(failingRequest, successInvoker());
        RpcResponse success = executor.execute(otherMethodRequest, successInvoker());

        assertEquals(ErrorCode.SERVICE_DEGRADED.getCode(), degraded.getCode());
        assertEquals(200, success.getCode());
    }

    @DisplayName("验证使用默认值降级On消费端侧场景")
    @Test
    void shouldUseDefaultValueDegradationOnConsumerSide() throws Exception {
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        circuitBreakerManager.clear();
        circuitBreakerManager.configure(50.0f, 1, 60000L, 1);
        circuitBreakerManager.getServiceCircuitBreaker("svc").recordFailure();
        FilterRuntimeConfig.configureConsumerDegradation(
                true,
                DegradationPolicyFactory.create("defaultValue", java.util.Map.of("svc#m", "consumer-fallback"))
        );

        RpcClientInvocationExecutor executor = new RpcClientInvocationExecutor(
                stubResolver(),
                circuitBreakerManager,
                new RetryExecutor(new DefaultRetryStrategy(), 0),
                request -> InvocationOptions.builder()
                        .retryTimes(0)
                        .clusterStrategy(ClusterStrategy.FAIL_FAST)
                        .rateLimitEnabled(false)
                        .rateLimitPermitsPerSecond(100)
                        .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                        .build(),
                new RateLimiterManager()
        );

        RpcResponse degraded = executor.execute(
                RpcRequest.builder().requestId("6").serviceName("svc").methodName("m").build(),
                successInvoker()
        );

        assertEquals(200, degraded.getCode());
        assertEquals("consumer-fallback", degraded.getData());
    }

    @DisplayName("验证不应用消费端降级当调用器过滤器禁用场景")
    @Test
    void shouldNotApplyConsumerDegradationWhenInvokerFilterDisabled() throws Exception {
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        circuitBreakerManager.clear();
        circuitBreakerManager.configure(50.0f, 1, 60000L, 1);
        circuitBreakerManager.getServiceCircuitBreaker("svc").recordFailure();
        FilterRuntimeConfig.configureConsumerDegradation(
                true,
                DegradationPolicyFactory.create("defaultValue", java.util.Map.of("svc#m", "consumer-fallback"))
        );

        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setInvokerFilters(java.util.List.of());
        FilterManager.configure(frameworkConfig);
        FilterRuntimeConfigurator.configureConsumer(new RpcFrameworkConfig(), null);

        RpcClientInvocationExecutor executor = new RpcClientInvocationExecutor(
                stubResolver(),
                circuitBreakerManager,
                new RetryExecutor(new DefaultRetryStrategy(), 0),
                request -> InvocationOptions.builder()
                        .retryTimes(0)
                        .clusterStrategy(ClusterStrategy.FAIL_FAST)
                        .rateLimitEnabled(false)
                        .rateLimitPermitsPerSecond(100)
                        .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                        .build(),
                new RateLimiterManager()
        );

        RpcResponse response = executor.execute(
                RpcRequest.builder().requestId("7").serviceName("svc").methodName("m").build(),
                successInvoker()
        );

        assertEquals(200, response.getCode());
        assertEquals("ok", response.getData());
        FilterManager.configure(new RpcFrameworkConfig());
        FilterRuntimeConfigurator.configureConsumer(new RpcFrameworkConfig(), null);
    }

    @DisplayName("验证使用注入的熔断器管理器用于服务并实例Breakers场景")
    @Test
    void shouldUseInjectedCircuitBreakerManagerForServiceAndInstanceBreakers() throws Exception {
        CircuitBreakerManager globalManager = CircuitBreakerManager.getInstance();
        globalManager.clear();
        CircuitBreakerManager isolatedManager = newCircuitBreakerManager();
        isolatedManager.configure(50.0f, 1, 60000L, 1);
        FilterRuntimeConfig.configureConsumerDegradation(false, null);

        RpcClientInvocationExecutor executor = new RpcClientInvocationExecutor(
                stubResolver(),
                isolatedManager,
                new RetryExecutor(new DefaultRetryStrategy(), 0),
                request -> InvocationOptions.builder()
                        .retryTimes(0)
                        .clusterStrategy(ClusterStrategy.FAIL_FAST)
                        .rateLimitEnabled(false)
                        .rateLimitPermitsPerSecond(100)
                        .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                        .build(),
                new RateLimiterManager()
        );

        assertThrows(RpcException.class, () -> executor.execute(
                RpcRequest.builder().requestId("8").serviceName("svc").methodName("m").build(),
                (request, address) -> {
                    throw new RpcException(ErrorCode.SERVER_ERROR, "boom");
                }
        ));

        assertEquals(0, globalManager.serviceBreakerCount());
        assertEquals(0, globalManager.instanceBreakerCount());
        assertEquals(1, isolatedManager.serviceBreakerCount());
        assertEquals(1, isolatedManager.instanceBreakerCount());
        assertEquals(CircuitBreakerState.OPEN, isolatedManager.getServiceCircuitBreaker("svc").getState());
        assertEquals(
                CircuitBreakerState.OPEN,
                isolatedManager.getInstanceCircuitBreaker("svc", new InetSocketAddress("127.0.0.1", 8080)).getState()
        );
    }

    @DisplayName("验证解析失败只统计到服务级熔断，并在故障恢复后保持服务级熔断关闭")
    @Test
    void shouldCountResolveFailureOnlyAtServiceLevelAndKeepServiceBreakerClosedAfterRecoveredFailover() throws Exception {
        CircuitBreakerManager isolatedManager = newCircuitBreakerManager();
        isolatedManager.configure(50.0f, 1, 60000L, 1);
        FilterRuntimeConfig.configureConsumerDegradation(false, null);

        RpcClientInvocationExecutor resolveFailExecutor = new RpcClientInvocationExecutor(
                resolverThatFailsBeforeAddressSelection(),
                isolatedManager,
                new RetryExecutor(new DefaultRetryStrategy(), 0),
                request -> InvocationOptions.builder()
                        .retryTimes(0)
                        .clusterStrategy(ClusterStrategy.FAIL_FAST)
                        .rateLimitEnabled(false)
                        .rateLimitPermitsPerSecond(100)
                        .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                        .build(),
                new RateLimiterManager()
        );

        assertThrows(RpcException.class, () -> resolveFailExecutor.execute(
                RpcRequest.builder().requestId("9").serviceName("missing-svc").methodName("m").build(),
                successInvoker()
        ));

        assertEquals(1, isolatedManager.serviceBreakerCount());
        assertEquals(0, isolatedManager.instanceBreakerCount());
        assertEquals(CircuitBreakerState.OPEN, isolatedManager.getServiceCircuitBreaker("missing-svc").getState());

        CircuitBreakerManager failoverManager = newCircuitBreakerManager();
        failoverManager.configure(50.0f, 1, 60000L, 1);
        RpcClientInvocationExecutor failoverExecutor = new RpcClientInvocationExecutor(
                stubResolver(),
                failoverManager,
                new RetryExecutor(new DefaultRetryStrategy(), 1),
                request -> InvocationOptions.builder()
                        .retryTimes(1)
                        .clusterStrategy(ClusterStrategy.FAIL_OVER)
                        .rateLimitEnabled(false)
                        .rateLimitPermitsPerSecond(100)
                        .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                        .build(),
                new RateLimiterManager()
        );

        final int[] attempts = {0};
        RpcResponse response = failoverExecutor.execute(
                RpcRequest.builder().requestId("10").serviceName("svc").methodName("m").build(),
                (request, address) -> {
                    if (attempts[0]++ == 0) {
                        throw new RpcException(ErrorCode.CONNECTION_RESET, "transient");
                    }
                    return RpcResponse.success("ok", request.getRequestId());
                }
        );

        assertEquals(200, response.getCode());
        assertEquals(CircuitBreakerState.CLOSED, failoverManager.getServiceCircuitBreaker("svc").getState());
        assertEquals(1, failoverManager.instanceBreakerCount());
    }

    private RpcServiceResolver stubResolver() throws Exception {
        return new RpcServiceResolverStub();
    }

    private RpcServiceResolver resolverThatFailsBeforeAddressSelection() throws Exception {
        return new ResolveFailingRpcServiceResolverStub();
    }

    private RpcTransportInvoker successInvoker() {
        return (request, address) -> RpcResponse.success("ok", request.getRequestId());
    }

    private static CircuitBreakerManager newCircuitBreakerManager() throws Exception {
        Constructor<CircuitBreakerManager> constructor = CircuitBreakerManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static final class RpcServiceResolverStub extends RpcServiceResolver {
        private RpcServiceResolverStub() {
            super(null, null, CircuitBreakerManager.getInstance());
        }

        @Override
        public InetSocketAddress resolve(String serviceName, String loadBalancerName) throws RpcException {
            return new InetSocketAddress("127.0.0.1", 8080);
        }
    }

    private static final class ResolveFailingRpcServiceResolverStub extends RpcServiceResolver {
        private ResolveFailingRpcServiceResolverStub() {
            super(null, null, CircuitBreakerManager.getInstance());
        }

        @Override
        public InetSocketAddress resolve(String serviceName, String loadBalancerName) throws RpcException {
            throw new RpcException(ErrorCode.SERVICE_NOT_FOUND, "Service not found: " + serviceName);
        }
    }
}

