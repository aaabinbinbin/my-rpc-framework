package com.rpc.proxy;

import com.rpc.HelloService;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 代理测试
 */
@Slf4j
public class RpcProxyTest {
    public static void main(String[] args) {
        // 1. 创建代理对象
        HelloService service = RpcProxyFactory.createProxy(HelloService.class);

        // 2. 调用方法
        String result1 = service.sayHello("world");
        log.info("调用结果 1: {}", result1);

        String result2 = service.sayHi("rpc framework");
        log.info("调用结果 2: {}", result2);

        // todo 目前模拟测试返回的是String类型Data，执行该方法会报错
//        Integer result3 = service.add(10, 20);
//        log.info("调用结果 3: {}", result3);
    }
}
