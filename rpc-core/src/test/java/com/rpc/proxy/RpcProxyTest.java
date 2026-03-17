package com.rpc.proxy;

import com.rpc.HelloService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

/**
 * RPC 代理测试
 */
@Slf4j
public class RpcProxyTest {
    @Test
    public void testJDKProxy() {
        long start = System.currentTimeMillis();
        // 1. 创建代理对象
        HelloService service = RpcProxyFactory.createProxyBySDK(HelloService.class);

        // 2. 调用方法
        String result1 = service.sayHello("world");
        log.info("调用结果 1: {}", result1);

        String result2 = service.sayHi("rpc framework");
        log.info("调用结果 2: {}", result2);

        long time = System.currentTimeMillis() - start;
        log.info("JDK代理耗时: {}", time); // 133

        // todo 目前模拟测试返回的是String类型Data，执行该方法会报错
//        Integer result3 = service.add(10, 20);
//        log.info("调用结果 3: {}", result3);
    }

    @Test
    public void testCGLibProxy() {
        long start = System.currentTimeMillis();
        // 1. 创建代理对象
        HelloService service = RpcProxyFactory.createProxyByCGLib(HelloService.class);
        // 2. 调用方法
        String result1 = service.sayHello("world");
        log.info("调用结果 1: {}", result1);

        String result2 = service.sayHi("rpc framework");
        log.info("调用结果 2: {}", result2);

        long time = System.currentTimeMillis() - start;
        log.info("JDK代理耗时: {}", time); //216
    }
}
