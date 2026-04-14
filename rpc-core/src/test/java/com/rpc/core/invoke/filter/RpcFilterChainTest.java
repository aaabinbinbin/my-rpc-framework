package com.rpc.core.invoke.filter;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.core.invoke.context.RpcContext;
import com.rpc.core.invoke.filter.runtime.FilterManager;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfig;
import com.rpc.core.invoke.filter.runtime.FilterRuntimeConfigurator;
import com.rpc.core.resilience.degrade.DegradationPolicyFactory;
import com.rpc.core.resilience.degrade.FailFastDegradation;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.invoke.proxy.impl.RpcInvocationHandler;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.runtime.server.ServerLifecycle;
import com.rpc.core.transport.RpcTransport;
import com.rpc.core.transport.netty.server.dispatch.RpcRequestExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：RPC过滤器链路测试")
class RpcFilterChainTest {
    @DisplayName("验证添加链路IDOn消费端侧场景")
    @Test
    void shouldAddTraceIdOnConsumerSide() {
        CapturingTransport transport = new CapturingTransport();
        EchoService proxy = (EchoService) Proxy.newProxyInstance(
                EchoService.class.getClassLoader(),
                new Class[]{EchoService.class},
                new RpcInvocationHandler(EchoService.class, transport)
        );

        assertEquals("ok", proxy.echo("hello"));
        assertNotNull(transport.capturedRequest.getAttachments().get("traceId"));
    }

    @DisplayName("验证恢复链路IDOn服务端侧场景")
    @Test
    void shouldRestoreTraceIdOnProviderSide() {
        FilterManager.configure(new RpcFrameworkConfig());
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        RpcRequestExecutor executor = new RpcRequestExecutor(
                new SingleServiceRegistry(new EchoServiceImpl()),
                new ServerLifecycle()
        );
        RpcRequest request = RpcRequest.builder()
                .requestId("req-1")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();
        request.getAttachments().put("traceId", "trace-123");

        RpcResponse response = executor.execute(request);

        assertEquals(200, response.getCode());
        assertEquals("trace-123", response.getData());
        executorService.shutdownNow();
    }

    @DisplayName("验证限流限制On服务端侧场景")
    @Test
    void shouldRateLimitOnProviderSide() {
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setServerRateLimitEnabled(true);
        frameworkConfig.setServerRateLimitPermitsPerSecond(1);
        FilterManager.configure(frameworkConfig);
        FilterRuntimeConfigurator.configureProvider(frameworkConfig, null);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        RpcRequestExecutor executor = new RpcRequestExecutor(
                new SingleServiceRegistry(new EchoServiceImpl()),
                new ServerLifecycle()
        );

        RpcRequest first = RpcRequest.builder()
                .requestId("req-2")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();
        RpcRequest second = RpcRequest.builder()
                .requestId("req-3")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();

        RpcResponse firstResponse = executor.execute(first);
        RpcResponse secondResponse = executor.execute(second);

        assertEquals(200, firstResponse.getCode());
        assertEquals(ErrorCode.RATE_LIMIT_EXCEEDED.getCode(), secondResponse.getCode());
        executorService.shutdownNow();
        FilterManager.configure(new RpcFrameworkConfig());
        FilterRuntimeConfigurator.configureProvider(new RpcFrameworkConfig(), null);
    }

    @DisplayName("验证降级On服务端侧当已配置场景")
    @Test
    void shouldDegradeOnProviderSideWhenConfigured() {
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setServerRateLimitEnabled(true);
        frameworkConfig.setServerRateLimitPermitsPerSecond(1);
        frameworkConfig.setServerDegradationEnabled(true);
        FilterManager.configure(frameworkConfig);
        FilterRuntimeConfig.configureProviderDegradation(true, new FailFastDegradation());
        FilterRuntimeConfigurator.configureProvider(frameworkConfig, new FailFastDegradation());

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        RpcRequestExecutor executor = new RpcRequestExecutor(
                new SingleServiceRegistry(new EchoServiceImpl()),
                new ServerLifecycle()
        );

        RpcRequest first = RpcRequest.builder()
                .requestId("req-4")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();
        RpcRequest second = RpcRequest.builder()
                .requestId("req-5")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();

        RpcResponse firstResponse = executor.execute(first);
        RpcResponse secondResponse = executor.execute(second);

        assertEquals(200, firstResponse.getCode());
        assertEquals(ErrorCode.SERVICE_DEGRADED.getCode(), secondResponse.getCode());
        executorService.shutdownNow();
        FilterManager.configure(new RpcFrameworkConfig());
        FilterRuntimeConfig.configureProviderDegradation(false, null);
        FilterRuntimeConfigurator.configureProvider(new RpcFrameworkConfig(), null);
    }

    @DisplayName("验证遵循已配置服务端Filters场景")
    @Test
    void shouldRespectConfiguredProviderFilters() {
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setServerRateLimitEnabled(true);
        frameworkConfig.setServerRateLimitPermitsPerSecond(1);
        frameworkConfig.setProviderFilters(List.of("providerMdc", "providerMetrics"));
        FilterManager.configure(frameworkConfig);
        FilterRuntimeConfigurator.configureProvider(frameworkConfig, null);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        RpcRequestExecutor executor = new RpcRequestExecutor(
                new SingleServiceRegistry(new EchoServiceImpl()),
                new ServerLifecycle()
        );

        RpcRequest first = RpcRequest.builder()
                .requestId("req-6")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();
        RpcRequest second = RpcRequest.builder()
                .requestId("req-7")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();

        RpcResponse firstResponse = executor.execute(first);
        RpcResponse secondResponse = executor.execute(second);

        assertEquals(200, firstResponse.getCode());
        assertEquals(200, secondResponse.getCode());
        executorService.shutdownNow();
        FilterManager.configure(new RpcFrameworkConfig());
        FilterRuntimeConfigurator.configureProvider(new RpcFrameworkConfig(), null);
    }

    @DisplayName("验证使用默认值降级On服务端侧场景")
    @Test
    void shouldUseDefaultValueDegradationOnProviderSide() {
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setServerRateLimitEnabled(true);
        frameworkConfig.setServerRateLimitPermitsPerSecond(1);
        frameworkConfig.setServerDegradationEnabled(true);
        FilterManager.configure(frameworkConfig);
        FilterRuntimeConfigurator.configureProvider(
                frameworkConfig,
                DegradationPolicyFactory.create(
                        "defaultValue",
                        java.util.Map.of(EchoService.class.getName() + "#echo", "fallback-value")
                )
        );

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        RpcRequestExecutor executor = new RpcRequestExecutor(
                new SingleServiceRegistry(new EchoServiceImpl()),
                new ServerLifecycle()
        );

        RpcRequest first = RpcRequest.builder()
                .requestId("req-8")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();
        RpcRequest second = RpcRequest.builder()
                .requestId("req-9")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();

        RpcResponse firstResponse = executor.execute(first);
        RpcResponse secondResponse = executor.execute(second);

        assertEquals(200, firstResponse.getCode());
        assertEquals(200, secondResponse.getCode());
        assertEquals("fallback-value", secondResponse.getData());
        executorService.shutdownNow();
        FilterManager.configure(new RpcFrameworkConfig());
        FilterRuntimeConfig.configureProviderDegradation(false, null);
        FilterRuntimeConfigurator.configureProvider(new RpcFrameworkConfig(), null);
    }

    @DisplayName("验证不应用服务端降级当限流限制过滤器禁用场景")
    @Test
    void shouldNotApplyProviderDegradationWhenRateLimitFilterDisabled() {
        RpcFrameworkConfig frameworkConfig = new RpcFrameworkConfig();
        frameworkConfig.setServerRateLimitEnabled(true);
        frameworkConfig.setServerRateLimitPermitsPerSecond(1);
        frameworkConfig.setServerDegradationEnabled(true);
        frameworkConfig.setProviderFilters(List.of("providerMdc", "providerMetrics"));
        FilterManager.configure(frameworkConfig);
        FilterRuntimeConfigurator.configureProvider(
                frameworkConfig,
                DegradationPolicyFactory.create(
                        "defaultValue",
                        java.util.Map.of(EchoService.class.getName() + "#echo", "fallback-value")
                )
        );

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        RpcRequestExecutor executor = new RpcRequestExecutor(
                new SingleServiceRegistry(new EchoServiceImpl()),
                new ServerLifecycle()
        );

        RpcRequest first = RpcRequest.builder()
                .requestId("req-10")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();
        first.getAttachments().put("traceId", "trace-a");
        RpcRequest second = RpcRequest.builder()
                .requestId("req-11")
                .serviceName(EchoService.class.getName())
                .methodName("echo")
                .parameterTypes(new Class[]{String.class})
                .parameters(new Object[]{"hello"})
                .build();
        second.getAttachments().put("traceId", "trace-b");

        RpcResponse firstResponse = executor.execute(first);
        RpcResponse secondResponse = executor.execute(second);

        assertEquals(200, firstResponse.getCode());
        assertEquals("trace-a", firstResponse.getData());
        assertEquals(200, secondResponse.getCode());
        assertEquals("trace-b", secondResponse.getData());
        executorService.shutdownNow();
        FilterManager.configure(new RpcFrameworkConfig());
        FilterRuntimeConfig.configureProviderDegradation(false, null);
        FilterRuntimeConfigurator.configureProvider(new RpcFrameworkConfig(), null);
    }

    public interface EchoService {
        String echo(String value);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String value) {
            return RpcContext.getContext().getTraceId();
        }
    }

    static class CapturingTransport implements RpcTransport {
        private RpcRequest capturedRequest;

        @Override
        public RpcResponse sendRequest(RpcRequest rpcRequest) {
            this.capturedRequest = rpcRequest;
            return RpcResponse.success("ok", rpcRequest.getRequestId());
        }

        @Override
        public void close() {
        }
    }

    static class SingleServiceRegistry implements LocalRegistry {
        private final Object service;

        SingleServiceRegistry(Object service) {
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
            return List.of(EchoService.class.getName());
        }
    }
}

