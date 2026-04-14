package com.rpc;

import com.rpc.core.api.annotation.RpcService;

import java.util.concurrent.atomic.AtomicLong;

@RpcService(HelloService.class)
public class HelloServiceImpl implements HelloService {
    private final AtomicLong unstableCounter = new AtomicLong();

    @Override
    public String sayHello(String name) {
        return "Hello, " + name + "!";
    }

    @Override
    public String sayHi(String name) {
        return "Hi, " + name + "! Nice to meet you!";
    }

    @Override
    public Integer add(Integer a, Integer b) {
        return a + b;
    }

    @Override
    public String echoPayload(String payload) {
        return payload;
    }

    @Override
    public String sleep(Long millis) {
        long safeMillis = millis == null ? 0L : Math.max(0L, Math.min(millis, 30_000L));
        try {
            Thread.sleep(safeMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("sleep interrupted", e);
        }
        return "slept " + safeMillis + " ms";
    }

    @Override
    public String unstable(String name, Integer failurePercent) {
        int percent = failurePercent == null ? 0 : Math.max(0, Math.min(failurePercent, 100));
        if (percent == 0) {
            return "stable: " + name;
        }
        long current = unstableCounter.incrementAndGet();
        int interval = Math.max(1, 100 / percent);
        if (current % interval == 0) {
            throw new IllegalStateException("planned unstable failure: percent=" + percent + ", counter=" + current);
        }
        return "unstable-ok: " + name + ", counter=" + current;
    }
}
