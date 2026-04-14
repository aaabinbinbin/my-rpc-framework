package com.rpc.core.runtime.server;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerLifecycle {
/**
 * 服务提供端的停机过程用两个维度描述：
 * 1. 是否还接收新请求
 * 2. 当前还有多少请求已经进入服务端处理链
 * 3. 当前还有多少请求正在真正执行
 */
    private final AtomicBoolean acceptingRequests = new AtomicBoolean(true);
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicInteger inflightRequests = new AtomicInteger(0);

    public boolean isAcceptingRequests() {
        return acceptingRequests.get();
    }

    public void stopAcceptingRequests() {
        acceptingRequests.set(false);
    }

    public void incrementActiveRequests() {
        activeRequests.incrementAndGet();
    }

    public void decrementActiveRequests() {
        activeRequests.decrementAndGet();
    }

    public int getActiveRequests() {
        return activeRequests.get();
    }

    public void incrementInflight() {
        inflightRequests.incrementAndGet();
    }

    public void decrementInflight() {
        inflightRequests.decrementAndGet();
    }

    public int getInflightRequests() {
        return inflightRequests.get();
    }

    public boolean awaitDrained(long timeout, TimeUnit unit) {
        // 这里用轻量轮询等待已经足够，
        // 因为 shutdown 本来就是低频路径，而且我们只关心活动请求与 inflight 是否归零。
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (activeRequests.get() == 0 && inflightRequests.get() == 0) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return activeRequests.get() == 0 && inflightRequests.get() == 0;
    }
}

