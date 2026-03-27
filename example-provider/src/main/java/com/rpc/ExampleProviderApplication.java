package com.rpc;

import com.rpc.bootstrap.RpcProviderBootstrap;

public class ExampleProviderApplication {
    public static void main(String[] args) throws Exception {
        RpcProviderBootstrap.fromConfig()
                .registerService(HelloService.class, new HelloServiceImpl())
                .start();
    }
}
