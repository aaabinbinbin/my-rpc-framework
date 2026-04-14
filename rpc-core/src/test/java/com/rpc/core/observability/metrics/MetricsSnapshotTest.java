package com.rpc.core.observability.metrics;

import com.rpc.core.runtime.server.BizThreadPool;
import com.rpc.core.runtime.server.ServerLifecycle;
import com.rpc.core.invoke.filter.context.FilterContext;
import com.rpc.core.invoke.filter.impl.ConsumerMetricsFilter;
import com.rpc.core.invoke.filter.impl.ProviderMetricsFilter;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：指标快照测试")
class MetricsSnapshotTest {
    @DisplayName("验证暴露服务指标快照场景")
    @Test
    void shouldExposeServiceMetricsSnapshot() {
        ServiceMetrics metrics = new ServiceMetrics();
        metrics.recordSuccess(100);
        metrics.recordFailure(300);

        ServiceMetrics.MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.getTotalCalls());
        assertEquals(1, snapshot.getFailedCalls());
        assertEquals(200, snapshot.getAverageLatencyNanos());
        assertEquals(300, snapshot.getLastLatencyNanos());
    }

    @DisplayName("验证暴露指标管理器快照场景")
    @Test
    void shouldExposeMetricsManagerSnapshot() {
        ServiceMetricsManager manager = ServiceMetricsManager.getInstance();
        manager.register("svc");
        manager.get("svc").recordSuccess(100);

        Map<String, ServiceMetrics.MetricsSnapshot> snapshot = manager.snapshotAll();
        assertTrue(snapshot.containsKey("svc"));
    }

    @DisplayName("验证记录非OK消费端响应为失败场景")
    @Test
    void shouldRecordNonOkConsumerResponseAsFailure() throws Exception {
        ServiceMetricsManager manager = ServiceMetricsManager.getInstance();
        manager.register("consumer-failed-svc");
        FilterContext context = FilterContext.builder()
                .request(RpcRequest.builder()
                        .serviceName("consumer-failed-svc")
                        .methodName("m")
                        .build())
                .build();

        new ConsumerMetricsFilter().invoke(
                context,
                ignored -> RpcResponse.fail(429, "rate limited", "1")
        );

        ServiceMetrics.MetricsSnapshot snapshot = manager.get("consumer-failed-svc").snapshot();
        assertEquals(1, snapshot.getTotalCalls());
        assertEquals(1, snapshot.getFailedCalls());
        manager.remove("consumer-failed-svc");
    }

    @DisplayName("验证记录非OK服务端响应为失败场景")
    @Test
    void shouldRecordNonOkProviderResponseAsFailure() throws Exception {
        ServiceMetricsManager manager = ServiceMetricsManager.getInstance();
        manager.register("provider-failed-svc");
        FilterContext context = FilterContext.builder()
                .request(RpcRequest.builder()
                        .serviceName("provider-failed-svc")
                        .methodName("m")
                        .build())
                .build();

        new ProviderMetricsFilter().invoke(
                context,
                ignored -> RpcResponse.fail(429, "rate limited", "1")
        );

        ServiceMetrics.MetricsSnapshot snapshot = manager.get("provider-failed-svc").snapshot();
        assertEquals(1, snapshot.getTotalCalls());
        assertEquals(1, snapshot.getFailedCalls());
        manager.remove("provider-failed-svc");
    }

    @DisplayName("验证暴露组合Observability快照场景")
    @Test
    void shouldExposeCombinedObservabilitySnapshot() {
        ServiceMetricsManager serviceMetricsManager = ServiceMetricsManager.getInstance();
        ClientRuntimeMetricsManager clientRuntimeMetricsManager = ClientRuntimeMetricsManager.getInstance();
        serviceMetricsManager.register("svc");
        serviceMetricsManager.get("svc").recordFailure(300);
        clientRuntimeMetricsManager.getMetrics().recordPendingLimitRejection();

        ObservabilitySnapshot snapshot = ObservabilitySnapshot.capture();

        assertTrue(snapshot.getServiceMetrics().containsKey("svc"));
        assertEquals(1, snapshot.getClientRuntime().getPendingLimitRejections());

        serviceMetricsManager.remove("svc");
        clientRuntimeMetricsManager.reset();
    }

    @DisplayName("验证暴露服务端运行时快照场景")
    @Test
    void shouldExposeServerRuntimeSnapshot() {
        ServerLifecycle lifecycle = new ServerLifecycle();
        lifecycle.incrementInflight();
        ThreadPoolExecutor executor = (ThreadPoolExecutor) BizThreadPool.create(1, 1, 10);

        ServerRuntimeMetrics.Snapshot snapshot = new ServerRuntimeMetrics(lifecycle, executor).snapshot();
        assertTrue(snapshot.isAcceptingRequests());
        assertEquals(1, snapshot.getInflightRequests());
        executor.shutdownNow();
    }

    @DisplayName("验证校正非法业务线程池配置场景")
    @Test
    void shouldSanitizeInvalidBizThreadPoolConfig() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) BizThreadPool.create(0, 0, 0);

        assertEquals(1, executor.getCorePoolSize());
        assertEquals(1, executor.getMaximumPoolSize());
        assertEquals(1, executor.getQueue().remainingCapacity());
        executor.shutdownNow();
    }
}

