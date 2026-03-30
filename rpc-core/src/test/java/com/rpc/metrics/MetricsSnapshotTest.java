package com.rpc.core.observability.metrics;

import com.rpc.core.runtime.server.BizThreadPool;
import com.rpc.core.runtime.server.ServerLifecycle;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsSnapshotTest {
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

    @Test
    void shouldExposeMetricsManagerSnapshot() {
        ServiceMetricsManager manager = ServiceMetricsManager.getInstance();
        manager.register("svc");
        manager.get("svc").recordSuccess(100);

        Map<String, ServiceMetrics.MetricsSnapshot> snapshot = manager.snapshotAll();
        assertTrue(snapshot.containsKey("svc"));
    }

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
}

