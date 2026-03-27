package com.rpc.metrics;

public class ServiceMetrics {
    private long totalCalls;
    private long failedCalls;

    public synchronized void recordSuccess() {
        totalCalls++;
    }

    public synchronized void recordFailure() {
        totalCalls++;
        failedCalls++;
    }
}
