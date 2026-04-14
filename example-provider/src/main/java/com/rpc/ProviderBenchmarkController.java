package com.rpc;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/benchmark/provider")
public class ProviderBenchmarkController {
    private final HelloService helloService;

    public ProviderBenchmarkController(HelloService helloService) {
        this.helloService = helloService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("side", "provider");
        result.put("time", Instant.now().toString());
        return result;
    }

    @GetMapping("/direct/hello")
    public Map<String, Object> directHello(@RequestParam(defaultValue = "provider") String name) {
        long start = System.nanoTime();
        String data = helloService.sayHello(name);
        return response("directHello", data, start);
    }

    @GetMapping("/direct/add")
    public Map<String, Object> directAdd(@RequestParam(defaultValue = "1") Integer a,
                                         @RequestParam(defaultValue = "2") Integer b) {
        long start = System.nanoTime();
        Integer data = helloService.add(a, b);
        return response("directAdd", data, start);
    }

    @GetMapping("/direct/sleep")
    public Map<String, Object> directSleep(@RequestParam(defaultValue = "100") Long millis) {
        long start = System.nanoTime();
        String data = helloService.sleep(millis);
        return response("directSleep", data, start);
    }

    private Map<String, Object> response(String operation, Object data, long startNanos) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("side", "provider");
        result.put("data", data);
        result.put("elapsedMicros", (System.nanoTime() - startNanos) / 1_000L);
        return result;
    }
}
