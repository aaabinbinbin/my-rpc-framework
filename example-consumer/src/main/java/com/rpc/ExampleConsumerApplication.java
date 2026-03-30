package com.rpc;

import com.rpc.core.api.annotation.RpcReference;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class ExampleConsumerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExampleConsumerApplication.class, args);
    }

    @Component
    static class ConsumerRunner implements ApplicationRunner {
        @RpcReference
        private HelloService helloService;

        @Override
        public void run(ApplicationArguments args) {
            System.out.println(helloService.sayHello("consumer"));
            System.out.println("1 + 2 = " + helloService.add(1, 2));
        }
    }
}
