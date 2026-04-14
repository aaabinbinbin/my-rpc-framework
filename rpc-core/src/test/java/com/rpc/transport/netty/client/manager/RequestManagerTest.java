package com.rpc.transport.netty.client.manager;

import com.rpc.core.common.exception.dedicated.ClientOverloadedException;
import com.rpc.core.transport.netty.client.request.RequestManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：请求管理器测试")
class RequestManagerTest {
    @DisplayName("验证清理超时待处理请求场景")
    @Test
    void shouldClearTimedOutPendingRequests() {
        RequestManager requestManager = new RequestManager();
        EmbeddedChannel channel = new EmbeddedChannel();
        CompletableFuture<?> future = requestManager.addRequest(1L, channel, 10L);

        requestManager.clearTimeoutRequests(System.currentTimeMillis() + 20L);

        assertTrue(future.isCompletedExceptionally());
        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(TimeoutException.class, thrown.getCause());
        assertEquals(0, requestManager.getPendingCount());

        channel.finishAndReleaseAll();
    }

    @DisplayName("验证保持未过期待处理请求场景")
    @Test
    void shouldKeepUnexpiredPendingRequests() {
        RequestManager requestManager = new RequestManager();
        EmbeddedChannel channel = new EmbeddedChannel();
        CompletableFuture<?> future = requestManager.addRequest(2L, channel, 1_000L);

        requestManager.clearTimeoutRequests(System.currentTimeMillis());

        assertTrue(!future.isDone());
        assertEquals(1, requestManager.getPendingCount());

        channel.finishAndReleaseAll();
    }

    @DisplayName("验证拒绝当待处理请求达到全局限制场景")
    @Test
    void shouldRejectWhenPendingRequestsReachGlobalLimit() {
        RequestManager requestManager = new RequestManager(1);
        EmbeddedChannel channel = new EmbeddedChannel();

        requestManager.addRequest(1L, channel, 1_000L);

        ClientOverloadedException thrown = assertThrows(
                ClientOverloadedException.class,
                () -> requestManager.addRequest(2L, channel, 1_000L)
        );
        assertEquals("Too many pending RPC requests", thrown.getMessage());

        channel.finishAndReleaseAll();
    }

    @DisplayName("验证强制执行待处理请求限制在并发场景")
    @Test
    void shouldEnforcePendingRequestLimitUnderConcurrency() throws Exception {
        RequestManager requestManager = new RequestManager(1);
        EmbeddedChannel channel = new EmbeddedChannel();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            for (int i = 0; i < 2; i++) {
                long requestId = i + 1;
                executor.submit(() -> {
                    await(start);
                    try {
                        requestManager.addRequest(requestId, channel, 1_000L);
                        accepted.incrementAndGet();
                    } catch (ClientOverloadedException e) {
                        rejected.incrementAndGet();
                    }
                });
            }

            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));

            assertEquals(1, accepted.get());
            assertEquals(1, rejected.get());
            assertEquals(1, requestManager.getPendingCount());
        } finally {
            executor.shutdownNow();
            channel.finishAndReleaseAll();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
