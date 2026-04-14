# my-rpc-framework

一个 Maven 多模块 Java RPC 框架示例项目，目标是让 consumer 像调用本地接口一样调用 provider 上的远程服务。

项目包含动态代理、自定义协议、Netty 通信、ZooKeeper 注册发现、负载均衡、过滤器、熔断、限流、降级、重试、Spring 集成和 Spring Boot 自动装配。

## 模块

| 模块 | 职责 |
| --- | --- |
| `rpc-core` | RPC 核心引擎，包含代理、配置、注册发现、协议、传输、容错治理、扩展点和指标 |
| `rpc-spring` | Spring 生命周期集成，处理 `@RpcReference` 和 `@RpcService` |
| `rpc-spring-boot-starter` | Spring Boot 自动装配、配置绑定和可观测端点 |
| `example-api` | provider 和 consumer 共用的服务契约 |
| `example-provider` | 服务提供方示例 |
| `example-consumer` | 服务调用方示例 |
| `example-benchmark` | 纯 RPC 压测客户端，直接通过 RPC 代理压 provider |

## 环境

建议使用 JDK 16 或更高版本。

根 `pom.xml` 仍保留 Java 11 编译属性，但 `rpc-core` 和 `rpc-spring-boot-starter` 当前显式使用 source/target 16，按 JDK 16+ 构建更稳。

还需要：

1. Maven
2. ZooKeeper，默认地址可通过 `RPC_REGISTRY_ADDRESS` 覆盖

## 快速开始

构建和测试：

```bash
mvn clean test
```

本地 ZooKeeper 示例。

Windows cmd：

```bat
set RPC_REGISTRY_ADDRESS=127.0.0.1:2181
```

PowerShell：

```powershell
$env:RPC_REGISTRY_ADDRESS = "127.0.0.1:2181"
```

Bash：

```bash
export RPC_REGISTRY_ADDRESS=127.0.0.1:2181
```

先启动 provider：

```bash
mvn -pl example-provider -am spring-boot:run
```

再启动 consumer：

```bash
mvn -pl example-consumer -am spring-boot:run
```

示例配置：

- `example-provider/src/main/resources/application.yml`
- `example-consumer/src/main/resources/application.yml`

## 一次调用的主线

```text
ExampleConsumerApplication
-> @RpcReference 代理对象
-> RpcInvocationHandler
-> RpcRequest
-> consumer filter chain
-> RpcClientInvocationExecutor
-> 服务发现 / 负载均衡 / cluster / retry / 熔断
-> RpcNettyClient
-> RpcProtocolEncoder
-> provider
-> RpcRequestDispatcher
-> RpcRequestExecutor
-> LocalRegistryImpl
-> HelloServiceImpl
-> RpcResponse
```

## 文档

文档分两层：

1. 教材层：适合从零开始学习项目。
   入口：[doc/README.md](./doc/README.md)
2. 知识库层：适合快速查询模块、类、调用链、配置和排障。
   入口：[doc/kb/00-index.md](./doc/kb/00-index.md)
3. 测试层：适合按步骤验证项目并记录测试结果。
   入口：[doc/05-testing/README.md](./doc/05-testing/README.md)

建议第一次阅读：

1. [完整学习主线](./doc/00-complete-learning-path.md)
2. [当前实现快照](./doc/01-start-here/05-current-implementation-snapshot.md)
3. [一次 RPC 调用的完整鸟瞰图](./doc/02-main-story/01-one-rpc-call-overview.md)
4. [源码阅读顺序](./doc/03-source-reading/01-reading-order.md)

## 排障入口

常见问题集中在：

- 注册中心地址不一致
- ZooKeeper 未启动
- provider 未先启动
- JDK 版本低于当前子模块编译要求
- `@RpcService` 未被 Spring 扫描
- `@RpcReference` 字段没有被 RPC Spring 管理器处理

详见：[常见问题与排障](./doc/kb/07-troubleshooting.md)

## 运行状态页面

示例应用现在会随 Spring Boot Web 启动内置运行状态页面。

provider：

```text
http://127.0.0.1:18080/rpc/observability/dashboard
```

consumer：

```text
http://127.0.0.1:18081/rpc/observability/dashboard
```

页面轮询同进程的 `/rpc/observability` JSON 端点，适合压测时观察调用数、失败数、平均耗时、最近耗时、pending request 限制、重连失败等当前运行状态。

## 压测接口

浏览器压测控制台：

```text
http://127.0.0.1:18081/benchmark/console
```

该页面运行在 `example-consumer` 中，可以直接开始/停止 RPC 压测，并同时展示 consumer 本进程指标和 provider 指标。默认读取 provider 指标地址：

```text
http://127.0.0.1:18080/rpc/observability?includeServices=true&limit=200
```

纯 RPC 压测客户端：

```bash
mvn -pl example-benchmark -am package -DskipTests
java -jar ./example-benchmark/target/example-benchmark-1.0-SNAPSHOT.jar --registry=127.0.0.1:2181 --threads=50 --durationSeconds=300 --warmupSeconds=30 --method=hello
```

查看压测客户端参数：

```bash
java -jar ./example-benchmark/target/example-benchmark-1.0-SNAPSHOT.jar --help
```

consumer 侧 HTTP -> RPC 压测入口：

```text
http://127.0.0.1:18081/benchmark/rpc/hello?name=jmeter
http://127.0.0.1:18081/benchmark/rpc/add?a=1&b=2
http://127.0.0.1:18081/benchmark/rpc/payload?size=1024
http://127.0.0.1:18081/benchmark/rpc/sleep?millis=100
http://127.0.0.1:18081/benchmark/rpc/unstable?name=jmeter&failurePercent=10
```

provider 侧本地基线入口：

```text
http://127.0.0.1:18080/benchmark/provider/direct/hello?name=baseline
http://127.0.0.1:18080/benchmark/provider/direct/add?a=1&b=2
http://127.0.0.1:18080/benchmark/provider/direct/sleep?millis=100
```

详细 JMeter 配置看：[RPC 生产可用性与压测手册](./doc/05-testing/README.md)
