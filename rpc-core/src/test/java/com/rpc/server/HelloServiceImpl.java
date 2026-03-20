package com.rpc.server;

import com.rpc.HelloService;
import lombok.extern.slf4j.Slf4j;

/**
 * HelloService 实现类
 */
@Slf4j
public class HelloServiceImpl implements HelloService {

    @Override
    public String sayHello(String name) {
        log.info("收到 sayHello 请求：{}", name);
        return "Hello, " + name + "!";
    }

    @Override
    public String sayHi(String name) {
        log.info("收到 sayHi 请求：{}", name);
        return "Hi, " + name + "! Nice to meet you!";
    }

    @Override
    public Integer add(Integer a, Integer b) {
        log.info("收到 add 请求：{} + {}", a, b);
        return a + b;
    }
}
