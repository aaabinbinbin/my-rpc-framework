package com.rpc.core.runtime.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLifecycleTest {
    @Test
    void shouldStopAcceptingRequests() {
        ServerLifecycle lifecycle = new ServerLifecycle();
        assertTrue(lifecycle.isAcceptingRequests());
        lifecycle.stopAcceptingRequests();
        assertFalse(lifecycle.isAcceptingRequests());
    }

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
        });
        thread.start();

        assertTrue(lifecycle.awaitDrained(1, TimeUnit.SECONDS));
    }
}

