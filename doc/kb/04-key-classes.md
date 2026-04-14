# 关键类索引

## 1. 示例入口

| 类 | 路径 | 职责 |
| --- | --- | --- |
| `HelloService` | `example-api/src/main/java/com/rpc/HelloService.java` | consumer/provider 共用服务契约 |
| `HelloServiceImpl` | `example-provider/src/main/java/com/rpc/HelloServiceImpl.java` | provider 真实业务实现 |
| `ExampleProviderApplication` | `example-provider/src/main/java/com/rpc/ExampleProviderApplication.java` | provider Spring Boot 启动入口 |
| `ExampleConsumerApplication` | `example-consumer/src/main/java/com/rpc/ExampleConsumerApplication.java` | consumer Spring Boot 启动入口 |

## 2. Spring 集成

| 类 | 路径 | 职责 |
| --- | --- | --- |
| `EnableRpc` | `rpc-spring/src/main/java/com/rpc/spring/EnableRpc.java` | 非 Boot 场景启用 RPC |
| `RpcSpringRegistrar` | `rpc-spring/src/main/java/com/rpc/spring/RpcSpringRegistrar.java` | 注册 Spring 集成组件 |
| `RpcSpringManager` | `rpc-spring/src/main/java/com/rpc/spring/RpcSpringManager.java` | 接管 `@RpcReference` 注入、`@RpcService` 发布和生命周期 |
| `RpcSpringBootAutoConfiguration` | `rpc-spring-boot-starter/src/main/java/com/rpc/spring/boot/RpcSpringBootAutoConfiguration.java` | Boot 自动装配入口 |
| `RpcBootFrameworkProperties` | `rpc-spring-boot-starter/src/main/java/com/rpc/spring/boot/RpcBootFrameworkProperties.java` | 把 Boot 配置转成 core 配置 |
| `RpcObservabilityEndpoint` | `rpc-spring-boot-starter/src/main/java/com/rpc/spring/boot/RpcObservabilityEndpoint.java` | HTTP 可观测端点 |

## 3. Consumer 主线

| 类 | 路径 | 职责 |
| --- | --- | --- |
| `RpcConsumerBootstrap` | `rpc-core/src/main/java/com/rpc/core/api/bootstrap/RpcConsumerBootstrap.java` | 创建 consumer 运行时 |
| `RpcProxyFactory` | `rpc-core/src/main/java/com/rpc/core/invoke/proxy/RpcProxyFactory.java` | 创建 JDK/CGLIB 代理 |
| `RpcInvocationHandler` | `rpc-core/src/main/java/com/rpc/core/invoke/proxy/impl/RpcInvocationHandler.java` | JDK 代理调用入口 |
| `RpcClientInvocationExecutor` | `rpc-core/src/main/java/com/rpc/core/transport/netty/client/invocation/RpcClientInvocationExecutor.java` | consumer 调用编排 |
| `RpcServiceResolver` | `rpc-core/src/main/java/com/rpc/core/transport/netty/client/invocation/RpcServiceResolver.java` | 服务发现和地址选择 |
| `RpcNettyClient` | `rpc-core/src/main/java/com/rpc/core/transport/netty/client/RpcNettyClient.java` | Netty client 发送请求 |
| `RequestManager` | `rpc-core/src/main/java/com/rpc/core/transport/netty/client/request/RequestManager.java` | requestId 到等待响应的映射 |

## 4. Provider 主线

| 类 | 路径 | 职责 |
| --- | --- | --- |
| `RpcProviderBootstrap` | `rpc-core/src/main/java/com/rpc/core/api/bootstrap/RpcProviderBootstrap.java` | 创建 provider 运行时 |
| `RpcNettyServer` | `rpc-core/src/main/java/com/rpc/core/transport/netty/server/RpcNettyServer.java` | Netty server |
| `RpcRequestHandler` | `rpc-core/src/main/java/com/rpc/core/transport/netty/server/handler/RpcRequestHandler.java` | Netty 请求 handler |
| `RpcRequestDispatcher` | `rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestDispatcher.java` | 请求分发和线程池隔离 |
| `RpcRequestExecutor` | `rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestExecutor.java` | provider 本地执行入口 |
| `LocalRegistryImpl` | `rpc-core/src/main/java/com/rpc/core/registry/local/LocalRegistryImpl.java` | provider 进程内 serviceName 到对象映射 |
| `ServerLifecycle` | `rpc-core/src/main/java/com/rpc/core/runtime/server/ServerLifecycle.java` | provider 生命周期和优雅停机状态 |

## 5. 支撑能力

| 类 | 路径 | 职责 |
| --- | --- | --- |
| `RpcFrameworkConfig` | `rpc-core/src/main/java/com/rpc/core/config/framework/RpcFrameworkConfig.java` | 框架总配置 |
| `FilterManager` | `rpc-core/src/main/java/com/rpc/core/invoke/filter/runtime/FilterManager.java` | 过滤器管理 |
| `DefaultFilterChain` | `rpc-core/src/main/java/com/rpc/core/invoke/filter/runtime/DefaultFilterChain.java` | 过滤链执行 |
| `RpcHeader` | `rpc-core/src/main/java/com/rpc/core/protocol/message/RpcHeader.java` | 协议头 |
| `RpcMessage` | `rpc-core/src/main/java/com/rpc/core/protocol/message/RpcMessage.java` | 协议消息外壳 |
| `RpcProtocolEncoder` | `rpc-core/src/main/java/com/rpc/core/protocol/codec/RpcProtocolEncoder.java` | 出站编码 |
| `RpcProtocolDecoder` | `rpc-core/src/main/java/com/rpc/core/protocol/codec/RpcProtocolDecoder.java` | 入站解码 |
| `ServiceRegistryFactory` | `rpc-core/src/main/java/com/rpc/core/registry/factory/ServiceRegistryFactory.java` | 创建注册中心和服务发现实现 |
| `ZooKeeperRegistryImpl` | `rpc-core/src/main/java/com/rpc/core/registry/zookeeper/ZooKeeperRegistryImpl.java` | ZooKeeper 注册发现实现 |

