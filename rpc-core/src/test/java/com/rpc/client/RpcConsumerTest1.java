package com.rpc.client;

import com.rpc.HelloService;
import com.rpc.proxy.RpcProxyFactory;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.transport.netty.client.RpcNettyClient;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 消费者测试（使用 ZooKeeper 作为注册中心）
 */
@Slf4j
public class RpcConsumerTest1 {
    public static void main(String[] args) {
        RpcNettyClient client = null;

        try {
            // 1. 创建 ZooKeeper 注册中心连接
            ServiceRegistry registry = new ZooKeeperRegistryImpl("8.134.204.101:2181", 5000);
            
            // 2. 创建并初始化客户端（传入注册中心）
            client = new RpcNettyClient(registry);
            RpcProxyFactory.initClient(client);

            // 3. 创建代理（不再需要指定 host 和 port，会从 ZooKeeper 获取）
            HelloService service = RpcProxyFactory.createProxy(
                    HelloService.class
            );

            // 3. 调用方法
            log.info("========== 测试 sayHello ==========");
            String result1 = service.sayHello("张三");
            log.info("结果：{}", result1);

            log.info("========== 测试 sayHi ==========");
            String result2 = service.sayHi("李四");
            log.info("结果：{}", result2);

            log.info("========== 测试 add ==========");
            Integer result3 = service.add(10, 20);
            log.info("结果：{}", result3);

            log.info("========== 所有测试完成 ==========");

        } catch (Exception e) {
            log.error("测试失败", e);
        } finally {
            // 4. 关闭客户端（会自动关闭 ZooKeeper 连接）
            if (client != null) {
                client.close();
            }
        }
    }
}
