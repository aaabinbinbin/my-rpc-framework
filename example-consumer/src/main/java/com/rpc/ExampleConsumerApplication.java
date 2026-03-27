package com.rpc;

import com.rpc.config.RpcClientConfig;
import com.rpc.proxy.RpcProxyFactory;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.transport.TransportType;

public class ExampleConsumerApplication {
    public static void main(String[] args) {
        String registryAddress = System.getProperty("rpc.registry", "127.0.0.1:2181");

        ServiceRegistry registry = new ZooKeeperRegistryImpl(registryAddress, 5000);
        RpcClientConfig clientConfig = RpcClientConfig.builder()
                .transportType(TransportType.from(System.getProperty("rpc.transport", "netty")))
                .build();

        RpcProxyFactory.initClient(clientConfig, registry);
        HelloService helloService = RpcProxyFactory.createProxy(HelloService.class);

        System.out.println(helloService.sayHello("consumer"));
        System.out.println("1 + 2 = " + helloService.add(1, 2));
    }
}
