package com.rpc.core.resilience;

import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import com.rpc.core.invoke.filter.runtime.FilterManager;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfigurator;
import com.rpc.core.invoke.invocation.CircuitBreakerScope;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.invoke.invocation.InvocationOptions;
import com.rpc.core.invoke.invocation.InvocationOptionsResolver;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.transport.netty.client.invocation.RpcClientInvocationExecutor;
import com.rpc.core.transport.netty.client.invocation.RpcServiceResolver;
import com.rpc.core.transport.netty.client.invocation.RpcTransportInvoker;
import com.rpc.core.transport.netty.server.dispatch.RpcRequestExecutor;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：组合降级流程测试")
class CombinedDegradationFlowTest {
    @DisplayName("验证支持消费端并服务端默认值降级在同时场景")
    @Test
    void shouldSupportConsumerAndProviderDefaultValueDegradationAtTheSameTime() throws Exception {
        CircuitBreakerManager circuitBreakerManager = CircuitBreakerManager.getInstance();
        circuitBreakerManager.clear();
        circuitBreakerManager.configure(50.0f, 1, 60000L, 1);
        circuitBreakerManager.getServiceCircuitBreaker("consumer-svc").recordFailure();

        RpcFrameworkConfig providerConfig = new RpcFrameworkConfig();
        providerConfig.setServerRateLimitEnabled(true);
        providerConfig.setServerRateLimitPermitsPerSecond(1);
        providerConfig.setServerDegradationEnabled(true);
        RpcFrameworkConfig consumerConfig = new RpcFrameworkConfig();
        consumerConfig.setEnableDegradation(true);
        FilterManager.configure(providerConfig);
        FilterRuntimeConfigurator.configureConsumer(
                consumerConfig,
                DegradationPolicyFactory.create(
                        "defaultValue",
                        Map.of("consumer-svc#query", "consumer-fallback")
                )
        );
        FilterRuntimeConfigurator.configureProvider(
                providerConfig,
                DegradationPolicyFactory.create(
                        "defaultValue",
                        Map.of(ProviderEchoService.class.getName() + "#echo", "provider-fallback")
                )
        );

        RpcClientInvocationExecutor consumerExecutor = new RpcClientInvocationExecutor(
                new StubResolver(),
                circuitBreakerManager,
                new com.rpc.core.resilience.retry.RetryExecutor(
                        new com.rpc.core.resilience.retry.DefaultRetryStrategy(),
                        0
                ),
                defaultInvocationOptions(),
                new com.rpc.core.resilience.ratelimit.RateLimiterManager()
        );

        RpcResponse consumerResponse = consumerExecutor.execute(
                RpcRequest.builder()
                        .requestId("consumer-1")
                        .serviceName("consumer-svc")
                        .methodName("query")
                        .build(),
                successInvoker()
        );

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        RpcRequestExecutor providerExecutor = new RpcRequestExecutor(
                new ProviderRegistry(new ProviderEchoServiceImpl()),
                new com.rpc.core.runtime.server.ServerLifecycle()
        );

        RpcRequest first = RpcRequest.builder()
                .requestId("provider-1")
                .serviceName(ProviderEchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();
        RpcRequest second = RpcRequest.builder()
                .requestId("provider-2")
                .serviceName(ProviderEchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();

        RpcResponse firstProviderResponse = providerExecutor.execute(first);
        RpcResponse secondProviderResponse = providerExecutor.execute(second);

        assertEquals(200, consumerResponse.getCode());
        assertEquals("consumer-fallback", consumerResponse.getData());
        assertEquals(200, firstProviderResponse.getCode());
        assertEquals("provider-real", firstProviderResponse.getData());
        assertEquals(200, secondProviderResponse.getCode());
        assertEquals("provider-fallback", secondProviderResponse.getData());

        executorService.shutdownNow();
        FilterManager.configure(new RpcFrameworkConfig());
        FilterRuntimeConfigurator.configureProvider(new RpcFrameworkConfig(), null);
        FilterRuntimeConfigurator.configureConsumer(new RpcFrameworkConfig(), null);
        circuitBreakerManager.clear();
    }

    private InvocationOptionsResolver defaultInvocationOptions() {
        return request -> InvocationOptions.builder()
                .retryTimes(0)
                .clusterStrategy(ClusterStrategy.FAIL_FAST)
                .rateLimitEnabled(false)
                .rateLimitPermitsPerSecond(100)
                .circuitBreakerScope(CircuitBreakerScope.SERVICE)
                .build();
    }

    private RpcTransportInvoker successInvoker() {
        return (request, address) -> RpcResponse.success("ok", request.getRequestId());
    }

    private static final class StubResolver extends RpcServiceResolver {
        private StubResolver() {
            super(null, null, CircuitBreakerManager.getInstance());
        }

        @Override
        public InetSocketAddress resolve(String serviceName, String loadBalancerName) {
            return new InetSocketAddress("127.0.0.1", 8080);
        }
    }

    public interface ProviderEchoService {
        String echo(String value);
    }

    public static final class ProviderEchoServiceImpl implements ProviderEchoService {
        @Override
        public String echo(String value) {
            return "provider-real";
        }
    }

    static final class ProviderRegistry implements com.rpc.core.registry.LocalRegistry {
        private final Object service;

        private ProviderRegistry(Object service) {
            this.service = service;
        }

        @Override
        public void register(String serviceName, Object serviceInstance) {
        }

        @Override
        public void unregister(String serviceName) {
        }

        @Override
        public Object getService(String serviceName) {
            return service;
        }

        @Override
        public boolean contains(String serviceName) {
            return true;
        }

        @Override
        public Iterable<String> serviceNames() {
            return java.util.List.of(ProviderEchoService.class.getName());
        }
    }
}

