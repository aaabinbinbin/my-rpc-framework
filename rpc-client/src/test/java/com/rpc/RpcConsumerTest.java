package com.rpc;

import com.rpc.netty.RpcNettyClient;
import com.rpc.proxy.RpcProxyFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 消费者测试
 */
@Slf4j
public class RpcConsumerTest {
    public static void main(String[] args) {
        RpcNettyClient client = null;

        try {
            // 1. 创建并初始化客户端
            client = new RpcNettyClient();
            RpcProxyFactory.initClient(client);

            // 2. 创建代理
            HelloService service = RpcProxyFactory.createProxy(
                    HelloService.class,
                    "127.0.0.1",
                    8080
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
            // 4. 关闭客户端
            if (client != null) {
                client.close();
            }
        }
    }
}
