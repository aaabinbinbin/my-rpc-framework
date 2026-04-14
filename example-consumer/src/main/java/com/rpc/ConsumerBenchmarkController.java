package com.rpc;

import com.rpc.core.api.annotation.RpcReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/benchmark/rpc")
public class ConsumerBenchmarkController {
    @RpcReference
    private HelloService helloService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("side", "consumer");
        result.put("time", Instant.now().toString());
        return result;
    }

    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(defaultValue = "jmeter") String name) {
        long start = System.nanoTime();
        String data = helloService.sayHello(name);
        return response("hello", data, start);
    }

    @GetMapping("/add")
    public Map<String, Object> add(@RequestParam(defaultValue = "1") Integer a,
                                   @RequestParam(defaultValue = "2") Integer b) {
        long start = System.nanoTime();
        Integer data = helloService.add(a, b);
        return response("add", data, start);
    }

    @GetMapping("/payload")
    public Map<String, Object> payload(@RequestParam(defaultValue = "1024") Integer size) {
        int safeSize = Math.max(0, Math.min(size == null ? 0 : size, 1024 * 1024));
        String requestPayload = "x".repeat(safeSize);
        long start = System.nanoTime();
        String data = helloService.echoPayload(requestPayload);
        Map<String, Object> result = response("payload", data.length(), start);
        result.put("payloadBytes", safeSize);
        return result;
    }

    @GetMapping("/sleep")
    public Map<String, Object> sleep(@RequestParam(defaultValue = "100") Long millis) {
        long start = System.nanoTime();
        String data = helloService.sleep(millis);
        return response("sleep", data, start);
    }

    @GetMapping("/unstable")
    public Map<String, Object> unstable(@RequestParam(defaultValue = "jmeter") String name,
                                        @RequestParam(defaultValue = "10") Integer failurePercent) {
        long start = System.nanoTime();
        String data = helloService.unstable(name, failurePercent);
        return response("unstable", data, start);
    }

    @GetMapping("/batch")
    public Map<String, Object> batch(@RequestParam(defaultValue = "100") Integer count,
                                     @RequestParam(defaultValue = "hello") String operation,
                                     @RequestParam(defaultValue = "0") Long sleepMillis,
                                     @RequestParam(defaultValue = "128") Integer payloadSize) {
        int safeCount = Math.max(1, Math.min(count == null ? 1 : count, 10_000));
        int success = 0;
        int failure = 0;
        long start = System.nanoTime();
        for (int i = 0; i < safeCount; i++) {
            try {
                invokeOperation(operation, sleepMillis, payloadSize, i);
                success++;
            } catch (RuntimeException e) {
                failure++;
            }
        }
        Map<String, Object> result = response("batch-" + operation, "done", start);
        result.put("requested", safeCount);
        result.put("success", success);
        result.put("failure", failure);
        return result;
    }

    private void invokeOperation(String operation, Long sleepMillis, Integer payloadSize, int index) {
        if ("add".equals(operation)) {
            helloService.add(index, index + 1);
        } else if ("sleep".equals(operation)) {
            helloService.sleep(sleepMillis);
        } else if ("payload".equals(operation)) {
            int safePayloadSize = payloadSize == null ? 0 : Math.max(0, Math.min(payloadSize, 1024 * 1024));
            helloService.echoPayload("x".repeat(safePayloadSize));
        } else if ("unstable".equals(operation)) {
            helloService.unstable("batch-" + index, 10);
        } else {
            helloService.sayHello("batch-" + index);
        }
    }

    private Map<String, Object> response(String operation, Object data, long startNanos) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("side", "consumer");
        result.put("data", data);
        result.put("elapsedMicros", (System.nanoTime() - startNanos) / 1_000L);
        return result;
    }
}
