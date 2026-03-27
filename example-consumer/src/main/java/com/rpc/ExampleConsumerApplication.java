package com.rpc;

import com.rpc.bootstrap.RpcConsumerBootstrap;

public class ExampleConsumerApplication {
    public static void main(String[] args) {
        try (RpcConsumerBootstrap consumerBootstrap = RpcConsumerBootstrap.fromConfig()) {
            HelloService helloService = consumerBootstrap.getService(HelloService.class);
            System.out.println(helloService.sayHello("consumer"));
            System.out.println("1 + 2 = " + helloService.add(1, 2));
        }
    }
}
