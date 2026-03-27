package com.rpc.client;

import com.rpc.HelloService;
import com.rpc.config.RpcClientConfig;
import com.rpc.proxy.RpcProxyFactory;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.transport.TransportType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcConsumerTest {
    public static void main(String[] args) {
        try {
            ServiceRegistry registry = new ZooKeeperRegistryImpl("8.134.204.101:2181", 5000);
            RpcClientConfig config = RpcClientConfig.builder()
                    .transportType(TransportType.from(System.getProperty("rpc.transport", "netty")))
                    .build();

            RpcProxyFactory.initClient(config, registry);

            HelloService service = RpcProxyFactory.createProxy(HelloService.class);

            log.info("========== 测试 sayHello ==========");
            log.info("结果：{}", service.sayHello("张三"));

            log.info("========== 测试 sayHi ==========");
            log.info("结果：{}", service.sayHi("李四"));

            log.info("========== 测试 add ==========");
            log.info("结果：{}", service.add(10, 20));
        } catch (Exception e) {
            log.error("测试失败", e);
        }
    }
}
