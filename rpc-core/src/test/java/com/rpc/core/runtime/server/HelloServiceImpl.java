package com.rpc.core.runtime.server;

import com.rpc.HelloService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HelloService 瀹炵幇绫?
 */
public class HelloServiceImpl implements HelloService {
    private static final Logger log = LoggerFactory.getLogger(HelloServiceImpl.class);

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

    @Override
    public String echoPayload(String payload) {
        return payload;
    }

    @Override
    public String sleep(Long millis) {
        return "slept " + millis + " ms";
    }

    @Override
    public String unstable(String name, Integer failurePercent) {
        return "unstable-ok: " + name;
    }
}

