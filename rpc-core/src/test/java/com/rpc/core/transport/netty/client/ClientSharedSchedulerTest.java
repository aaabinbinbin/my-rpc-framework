package com.rpc.core.transport.netty.client;

import com.rpc.core.transport.netty.client.scheduler.ClientSharedScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：客户端共享调度器测试")
class ClientSharedSchedulerTest {
    @DisplayName("验证Share单个调度器跨客户端并关闭在最后释放场景")
    @Test
    void shouldShareSingleSchedulerAcrossClientsAndShutdownAfterLastRelease() throws Exception {
        ClientSharedScheduler first = ClientSharedScheduler.getInstance();
        ClientSharedScheduler second = ClientSharedScheduler.getInstance();
        CountDownLatch latch = new CountDownLatch(2);

        first.scheduleAtFixedRate(latch::countDown, 0L, 10L, TimeUnit.MILLISECONDS);
        second.scheduleAtFixedRate(latch::countDown, 0L, 10L, TimeUnit.MILLISECONDS);

        assertTrue(latch.await(1L, TimeUnit.SECONDS));
        assertEquals(2, first.referenceCount());
        assertEquals(2, second.referenceCount());

        first.release();
        assertEquals(1, second.referenceCount());

        second.release();
        assertEquals(0, second.referenceCount());
    }
}
