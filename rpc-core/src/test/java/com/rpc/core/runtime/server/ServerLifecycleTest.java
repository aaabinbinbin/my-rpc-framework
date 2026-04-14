package com.rpc.core.runtime.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端生命周期测试。
 *
 * <p>测试目标：验证优雅停机过程中的接收开关和在途请求计数逻辑。</p>
 */
@DisplayName("服务端生命周期测试")
class ServerLifecycleTest {

    @DisplayName("停止接收请求后应拒绝新的请求进入")
    @Test
    void shouldStopAcceptingRequests() {
        ServerLifecycle lifecycle = new ServerLifecycle();
        assertTrue(lifecycle.isAcceptingRequests());
        lifecycle.stopAcceptingRequests();
        assertFalse(lifecycle.isAcceptingRequests());
    }

    @DisplayName("在途请求归零后等待排空应返回成功")
    @Test
    void shouldAwaitInflightDrain() {
        ServerLifecycle lifecycle = new ServerLifecycle();
        lifecycle.incrementInflight();

        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lifecycle.decrementInflight();
        }, "server-lifecycle-test-drainer");
        thread.start();

        assertTrue(lifecycle.awaitDrained(1, TimeUnit.SECONDS));
    }
}
