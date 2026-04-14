# 项目卡片

## 1. 一句话

这是一个 Maven 多模块 RPC 框架。它让 consumer 像调用本地接口一样调用 provider 上的远程服务，内部通过动态代理、自定义协议、Netty、注册发现、负载均衡、过滤器、熔断限流降级重试和 Spring Boot 自动装配把调用链跑通。

## 2. 模块

| 模块 | 职责 |
| --- | --- |
| `example-api` | 服务契约，提供 `HelloService` 和共享模型 |
| `example-provider` | provider 示例应用，提供 `HelloServiceImpl` |
| `example-consumer` | consumer 示例应用，通过 `@RpcReference` 发起调用 |
| `rpc-core` | 核心 RPC 引擎 |
| `rpc-spring` | Spring 生命周期集成 |
| `rpc-spring-boot-starter` | Spring Boot 自动装配、配置绑定、可观测端点 |

## 3. 技术栈

| 方向 | 当前实现 |
| --- | --- |
| 构建 | Maven 多模块 |
| JDK | 建议 JDK 16+；根 POM 仍保留 Java 11 属性，但 `rpc-core` 和 `rpc-spring-boot-starter` 编译插件当前使用 source/target 16 |
| 网络 | Netty，另有 legacy socket 实现 |
| 注册中心 | ZooKeeper，本地注册表用于 provider 进程内服务映射 |
| 序列化 | Java、JSON、Kryo、Hessian、Protobuf |
| Spring | Spring 5.3.x，Spring Boot 2.7.x |
| 治理 | 过滤器、限流、熔断、降级、重试、负载均衡、metrics |

## 4. 最小运行入口

1. 启动 ZooKeeper，或把 `RPC_REGISTRY_ADDRESS` 指向可用的 ZooKeeper 地址。
2. 启动 `example-provider`。
3. 启动 `example-consumer`。
4. 观察 consumer 输出 `sayHello` 和 `add` 的调用结果。

常用命令：

```bash
mvn clean test
mvn -pl example-provider -am spring-boot:run
mvn -pl example-consumer -am spring-boot:run
```

本地 ZooKeeper 常用覆盖：

```bash
RPC_REGISTRY_ADDRESS=127.0.0.1:2181
```

示例配置位置：

1. `example-provider/src/main/resources/application.yml`
2. `example-consumer/src/main/resources/application.yml`

## 5. 忽略目录

阅读源码和建立知识库时忽略：

1. `target/`
2. `.idea/`
3. `.git/`
4. `*.class`
5. surefire 历史报告和 dump 文件
