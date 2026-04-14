package com.rpc.core.transport.netty.client.handler.heartbeat;

import com.rpc.core.transport.netty.client.scheduler.ReconnectSharedScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：重连共享调度器测试")
class ReconnectSharedSchedulerTest {
    @DisplayName("验证Share单个重连调度器并释放在最后使用者场景")
    @Test
    void shouldShareSingleReconnectSchedulerAndReleaseAfterLastUser() throws Exception {
        ReconnectSharedScheduler first = ReconnectSharedScheduler.getInstance();
        ReconnectSharedScheduler second = ReconnectSharedScheduler.getInstance();
        CountDownLatch latch = new CountDownLatch(2);

        first.schedule(latch::countDown, 0L, TimeUnit.MILLISECONDS);
        second.schedule(latch::countDown, 0L, TimeUnit.MILLISECONDS);

        assertTrue(latch.await(1L, TimeUnit.SECONDS));
        assertEquals(2, first.referenceCount());
        assertEquals(2, second.referenceCount());

        first.release();
        assertEquals(1, second.referenceCount());

        second.release();
        assertEquals(0, second.referenceCount());
    }
}
