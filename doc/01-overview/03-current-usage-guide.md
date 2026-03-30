# 当前项目运行方式和配置说明

## 1. 项目模块

- `rpc-core`：RPC 核心能力，包含配置、协议、传输、注册中心、服务发现、过滤器、容错和代理。
- `rpc-spring`：Spring 集成层，负责把 `@RpcService`、`@RpcReference` 接入 Spring 生命周期。
- `rpc-spring-boot-starter`：Spring Boot 自动装配层，支持通过 `application.yml` 直接启用 RPC。
- `example-api`：示例服务接口。
- `example-provider`：基于 Spring Boot 的服务提供者示例。
- `example-consumer`：基于 Spring Boot 的服务消费者示例。

## 2. 当前推荐运行方式

当前项目推荐使用 Spring Boot 示例进行联调，不再建议手写 `main + bootstrap` 作为日常使用方式。

运行链路是：

1. 启动 ZooKeeper。
2. 启动 `example-provider`。
3. 启动 `example-consumer`。
4. consumer 通过注册中心发现 provider，并完成 RPC 调用。

## 3. 运行前准备

### 3.1 ZooKeeper

默认注册中心地址是：

```yaml
127.0.0.1:2181
```

如果本机没有 ZooKeeper，客户端或服务端启动时会出现连接拒绝错误，例如：

```text
Connection refused
```

这不是代码问题，而是注册中心地址可达性问题。

### 3.2 JDK 与 Maven

- JDK：当前 Maven 编译目标是 `16`
- Maven：使用项目根目录直接执行

## 4. 示例工程配置位置

### 4.1 provider

配置文件：

- [application.yml](/D:/aaaRPC/my-rpc-framework/example-provider/src/main/resources/application.yml)

启动类：

- [ExampleProviderApplication.java](/D:/aaaRPC/my-rpc-framework/example-provider/src/main/java/com/rpc/ExampleProviderApplication.java)

服务实现：

- [HelloServiceImpl.java](/D:/aaaRPC/my-rpc-framework/example-provider/src/main/java/com/rpc/HelloServiceImpl.java)

### 4.2 consumer

配置文件：

- [application.yml](/D:/aaaRPC/my-rpc-framework/example-consumer/src/main/resources/application.yml)

启动类：

- [ExampleConsumerApplication.java](/D:/aaaRPC/my-rpc-framework/example-consumer/src/main/java/com/rpc/ExampleConsumerApplication.java)

接口定义：

- [HelloService.java](/D:/aaaRPC/my-rpc-framework/example-api/src/main/java/com/rpc/HelloService.java)

## 5. 如何启动示例

### 5.1 使用默认本地 ZooKeeper

先启动 provider：

```powershell
mvn -pl example-provider -am spring-boot:run
```

再启动 consumer：

```powershell
mvn -pl example-consumer -am spring-boot:run
```

### 5.2 使用自定义 ZooKeeper 地址

可以通过环境变量覆盖：

```powershell
$env:RPC_REGISTRY_ADDRESS="192.168.1.10:2181"
```

然后再分别启动 provider 和 consumer。

也可以直接修改两个 example 下的 `application.yml`：

- [application.yml](/D:/aaaRPC/my-rpc-framework/example-provider/src/main/resources/application.yml)
- [application.yml](/D:/aaaRPC/my-rpc-framework/example-consumer/src/main/resources/application.yml)

## 6. 当前主要配置项

核心默认配置文件在：

- [rpc.properties](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/resources/rpc.properties)

Spring Boot 场景下，推荐直接在 `application.yml` 中配置 `rpc.*`，不必依赖 `rpc.properties`。

### 6.1 通用配置

```yaml
rpc:
  transport: netty
  serializer: protobuf
  loadbalancer: random
```

- `rpc.transport`
  - 可选：`netty`、`socket`
- `rpc.serializer`
  - 可选：`protobuf`、`kryo`、`json`、`java`、`hessian`
- `rpc.loadbalancer`
  - 可选：`random`、`roundRobin`、`leastConnections`、`consistentHash`

### 6.2 注册中心配置

```yaml
rpc:
  registry:
    type: zookeeper
    address: 127.0.0.1:2181
    timeout: 5000
```

- `rpc.registry.type`
  - 当前主要使用 `zookeeper`
- `rpc.registry.address`
  - 注册中心地址
- `rpc.registry.timeout`
  - 连接超时

### 6.3 provider 配置

```yaml
rpc:
  server:
    host: 127.0.0.1
    port: 8080
    scan-packages:
      - com.rpc
    auto-register-annotated-services: true
```

常用项：

- `rpc.server.host`
- `rpc.server.port`
- `rpc.server.scan-packages`
- `rpc.server.auto-register-annotated-services`
- `rpc.server.biz.core-threads`
- `rpc.server.biz.max-threads`
- `rpc.server.biz.queue-capacity`
- `rpc.server.rate-limit.enabled`
- `rpc.server.degradation.enabled`

### 6.4 consumer 配置

```yaml
rpc:
  client:
    connect-timeout: 5000
    read-timeout: 10000
    cluster: failover
```

常用项：

- `rpc.client.connect-timeout`
- `rpc.client.read-timeout`
- `rpc.client.retry-times`
- `rpc.client.cluster`
- `rpc.client.reconnect.enabled`
- `rpc.client.discovery.cache-ttl-millis`
- `rpc.client.discovery.allow-stale-on-failure`
- `rpc.client.rate-limit.enabled`
- `rpc.client.enable-degradation`

### 6.5 方法级配置

Spring Boot 下支持在 `rpc.client.methods` 中写方法级治理配置，例如：

```yaml
rpc:
  client:
    methods:
      - service-name: com.rpc.HelloService
        method-name: sayHello
        retry-times: 0
        cluster-strategy: failfast
        read-timeout: 500
        serializer-name: json
        load-balancer-name: roundRobin
        rate-limit-enabled: true
        rate-limit-permits-per-second: 10
        circuit-breaker-scope: method
```

适用场景：

- 某个方法需要更短超时
- 某个方法不允许重试
- 某个方法需要单独熔断或限流

## 7. 当前对外使用建议

### 7.1 provider 侧

推荐写法：

1. 服务实现类上加 `@RpcService`
2. Spring Boot 主类正常启动
3. 在 `application.yml` 中配置 `rpc.server.*` 和 `rpc.registry.*`

### 7.2 consumer 侧

推荐写法：

1. 字段上加 `@RpcReference`
2. Spring Boot 主类正常启动
3. 在 `application.yml` 中配置 `rpc.client.*` 和 `rpc.registry.*`

## 8. 常见问题

### 8.1 启动时出现 ZooKeeper `Connection refused`

原因：

- 本机没有启动 ZooKeeper
- `rpc.registry.address` 配错了
- 配置里是旧地址，当前机器无法访问

处理方法：

1. 确认 ZooKeeper 是否已启动
2. 确认 `rpc.registry.address` 是否正确
3. 优先使用环境变量 `RPC_REGISTRY_ADDRESS` 覆盖

### 8.2 为什么修改配置后没有生效

优先检查：

1. 是否改的是 example 自己的 `application.yml`
2. 是否有环境变量覆盖了配置
3. 是否同时存在 `rpc.properties` 和 Boot 配置，导致你误读了实际生效来源

当前建议是：

- Spring Boot 示例以 `application.yml` 为主
- `rpc.properties` 主要作为 core 默认值和非 Boot 场景兜底

## 9. 当前状态结论

当前项目已经具备以下使用方式：

- 非 Spring 场景：可用 bootstrap 手工接入
- Spring 场景：可用 `rpc-spring`
- Spring Boot 场景：可用 `rpc-spring-boot-starter`

如果你现在是自己联调和展示项目，最推荐直接使用 `example-provider` 和 `example-consumer` 这两个 Spring Boot 示例。
