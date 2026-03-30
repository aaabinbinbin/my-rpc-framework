package com.rpc.core.runtime.server;

import com.rpc.HelloService;
import lombok.extern.slf4j.Slf4j;

/**
 * HelloService 瀹炵幇绫?
 */
@Slf4j
public class HelloServiceImpl implements HelloService {

    @Override
    public String sayHello(String name) {
        log.info("鏀跺埌 sayHello 璇锋眰锛歿}", name);
        return "Hello, " + name + "!";
    }

    @Override
    public String sayHi(String name) {
        log.info("鏀跺埌 sayHi 璇锋眰锛歿}", name);
        return "Hi, " + name + "! Nice to meet you!";
    }

    @Override
    public Integer add(Integer a, Integer b) {
        log.info("鏀跺埌 add 璇锋眰锛歿} + {}", a, b);
        return a + b;
    }
}

