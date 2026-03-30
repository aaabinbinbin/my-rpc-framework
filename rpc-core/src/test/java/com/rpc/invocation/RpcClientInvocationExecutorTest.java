package com.rpc.core.invoke.invocation;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import com.rpc.core.resilience.degrade.FailFastDegradation;
import com.rpc.core.invoke.filter.FilterManager;
import com.rpc.core.invoke.filter.FilterRuntimeConfigurator;
import com.rpc.core.invoke.filter.FilterRuntimeConfig;
import com.rpc.core.resilience.ratelimit.RateLimiterManager;
import com.rpc.core.resilience.retry.DefaultRetryStrategy;
import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.transport.netty.client.invocation.RpcClientInvocationExecutor;
import com.rpc.core.transport.netty.client.invocation.RpcServiceResolver;
import com.rpc.core.transport.netty.client.invocation.RpcTransportInvoker;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcClientInvocationExecutorTest {
    @Test
    void shouldWriteMethodLevelOverridesIntoRequestAttachments() throws Exception {
        CircuitBreakerManager.getInstance().clear();
        FilterRuntimeConfig.configureConsumerDegradation(false, 10, null);
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

    @Test
    void shouldReturnRateLimitExceededWhenLimiterRejects() throws Exception {
        CircuitBreakerManager.getInstance().clear();
        FilterRuntimeConfig.configureConsumerDegradation(false, 10, null);
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

    @Test
    void shouldApplyDegradationWhenCircuitBreakerIsOpen() throws Exception {
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        circuitBreakerManager.clear();
        circuitBreakerManager.configure(50.0f, 1, 60000L, 1);
        circuitBreakerManager.getServiceCircuitBreaker("svc").recordFailure();
        FilterRuntimeConfig.configureConsumerDegradation(true, 1, new FailFastDegradation());

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

    @Test
    void shouldIsolateCircuitBreakerByMethodWhenScopeIsMethod() throws Exception {
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        circuitBreakerManager.clear();
        circuitBreakerManager.configure(50.0f, 1, 60000L, 1);
        FilterRuntimeConfig.configureConsumerDegradation(true, 1, new FailFastDegradation());

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

    @Test
    void shouldUseDefaultValueDegradationOnConsumerSide() throws Exception {
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        circuitBreakerManager.clear();
        circuitBreakerManager.configure(50.0f, 1, 60000L, 1);
        circuitBreakerManager.getServiceCircuitBreaker("svc").recordFailure();
        FilterRuntimeConfig.configureConsumerDegradation(
                true,
                1,
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

    @Test
    void shouldNotApplyConsumerDegradationWhenInvokerFilterDisabled() throws Exception {
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        circuitBreakerManager.clear();
        circuitBreakerManager.configure(50.0f, 1, 60000L, 1);
        circuitBreakerManager.getServiceCircuitBreaker("svc").recordFailure();
        FilterRuntimeConfig.configureConsumerDegradation(
                true,
                1,
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

    private RpcServiceResolver stubResolver() throws Exception {
        return new RpcServiceResolverStub();
    }

    private RpcTransportInvoker successInvoker() {
        return (request, address) -> RpcResponse.success("ok", request.getRequestId());
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
}

