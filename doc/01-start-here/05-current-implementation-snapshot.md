# 当前实现快照：学习前先对齐源码现状

这篇不是新课程，而是一个“对齐表”。它的作用是让你在阅读教材时，先知道当前源码已经拆成哪些模块和包，避免拿旧路径去找类。

## 1. 当前模块

```text
example-api
example-provider
example-consumer
rpc-core
rpc-spring
rpc-spring-boot-starter
```

第一遍先这样理解：

1. `example-api`：consumer 和 provider 共用的接口契约。
2. `example-provider`：服务提供方示例，真正持有 `HelloServiceImpl`。
3. `example-consumer`：服务调用方示例，持有 `@RpcReference` 字段并发起调用。
4. `rpc-core`：RPC 框架核心，包含代理、配置、注册发现、过滤器、协议、传输、容错和指标。
5. `rpc-spring`：把 `rpc-core` 接入 Spring 生命周期。
6. `rpc-spring-boot-starter`：Spring Boot 自动装配、配置绑定和可观测端点。

## 2. 当前 `rpc-core` 包结构

```text
api              注解、bootstrap、类路径扫描
common           错误码、异常、工具
config           配置对象、配置加载、配置绑定
discovery        服务发现缓存、服务目录、实例快照
extension        序列化、负载均衡、SPI
invoke           代理、过滤链、调用配置、cluster 策略
observability    client/server/service 运行指标
protocol         协议消息和编解码
registry         本地注册表、注册中心、ZooKeeper 适配
resilience       熔断、限流、降级、重试
runtime          服务端生命周期和业务线程池
transport        Netty 传输、遗留 socket 实现、请求执行
```

这里最容易过期的是路径。当前要按下面的新路径查：

1. 协议消息在 `rpc-core/src/main/java/com/rpc/core/protocol/message`。
2. 协议编解码在 `rpc-core/src/main/java/com/rpc/core/protocol/codec`。
3. 过滤器接口在 `rpc-core/src/main/java/com/rpc/core/invoke/filter/api`。
4. 过滤链运行时在 `rpc-core/src/main/java/com/rpc/core/invoke/filter/runtime`。
5. 过滤器上下文在 `rpc-core/src/main/java/com/rpc/core/invoke/filter/context`。
6. 本地注册表实现在 `rpc-core/src/main/java/com/rpc/core/registry/local`。
7. ZooKeeper 注册中心实现在 `rpc-core/src/main/java/com/rpc/core/registry/zookeeper`。
8. Netty client 请求管理在 `rpc-core/src/main/java/com/rpc/core/transport/netty/client/request`。
9. Netty 心跳在 `rpc-core/src/main/java/com/rpc/core/transport/netty/client/handler/heartbeat` 和 `rpc-core/src/main/java/com/rpc/core/transport/netty/server/handler/heartbeat`。
10. 原 socket 实现已经放到 `rpc-core/src/main/java/com/rpc/core/transport/socket/legacy`，第一遍不要从这里开始。

## 3. 第一遍最重要的主线类

按这个顺序打开源码：

1. `example-consumer/src/main/java/com/rpc/ExampleConsumerApplication.java`
2. `example-api/src/main/java/com/rpc/HelloService.java`
3. `example-provider/src/main/java/com/rpc/HelloServiceImpl.java`
4. `rpc-spring/src/main/java/com/rpc/spring/RpcSpringManager.java`
5. `rpc-core/src/main/java/com/rpc/core/api/bootstrap/RpcConsumerBootstrap.java`
6. `rpc-core/src/main/java/com/rpc/core/invoke/proxy/RpcProxyFactory.java`
7. `rpc-core/src/main/java/com/rpc/core/invoke/proxy/impl/RpcInvocationHandler.java`
8. `rpc-core/src/main/java/com/rpc/core/transport/netty/client/invocation/RpcClientInvocationExecutor.java`
9. `rpc-core/src/main/java/com/rpc/core/transport/netty/client/RpcNettyClient.java`
10. `rpc-core/src/main/java/com/rpc/core/api/bootstrap/RpcProviderBootstrap.java`
11. `rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestDispatcher.java`
12. `rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestExecutor.java`

这条线能先回答最重要的问题：

`一次 helloService.sayHello(...) 是怎么从本地接口调用变成 provider 上的真实方法执行的？`

## 4. 当前学习重点

初学者不要从 Netty pipeline、ZooKeeper watcher 或 SPI 细节直接切入。

更稳的顺序是：

1. 先跑通 provider 和 consumer 示例。
2. 先理解 `@RpcReference` 注入的不是普通对象，而是代理对象。
3. 再理解代理对象如何组装 `RpcRequest`。
4. 再理解 consumer 侧的过滤器、限流、熔断、cluster、重试和地址选择。
5. 再理解 transport 如何把请求发出去并用 requestId 回填响应。
6. 再理解 provider 如何从本地注册表找到服务对象并反射调用。
7. 最后回头看配置、SPI、负载均衡、序列化和可观测能力。

