# RPC 项目解耦与配置化重构说明

## 本次调整目标

本次重构重点解决了几个长期问题：

1. 层与层之间的调用边界不清晰，外部接入时需要知道太多底层细节。
2. SPI 已经存在，但没有真正进入“配置驱动”的使用路径。
3. 示例和测试里存在较多硬编码地址、传输方式、默认序列化器等，后续一旦修改内部参数容易出错。
4. 使用方创建消费者/提供者时样板代码过多。

## 调整后的核心结构

### 1. 配置层

新增统一配置模型与加载器：

- `com.rpc.config.RpcFrameworkConfig`
- `com.rpc.config.RpcConfigLoader`
- `com.rpc.config.RpcConfigKeys`

职责：

- 从 `classpath:rpc.properties` 读取框架配置
- 支持通过 `-Dxxx=...` 的系统属性覆盖配置文件
- 将“传输、注册中心、负载均衡、序列化、超时、线程数”等统一收口

这样做之后，配置读取只在配置层发生，业务入口不再直接散落 `System.getProperty(...)`。

### 2. 启动与外部接入层

新增两个高层 facade：

- `com.rpc.bootstrap.RpcConsumerBootstrap`
- `com.rpc.bootstrap.RpcProviderBootstrap`

职责：

- 对外暴露更高层的使用方式
- 内部串联配置加载、注册中心创建、传输工厂、代理工厂
- 让外部调用方只关注“我要消费什么服务”或“我要暴露什么服务”

现在外部使用可以简化为：

```java
try (RpcConsumerBootstrap consumer = RpcConsumerBootstrap.fromConfig()) {
    HelloService service = consumer.getService(HelloService.class);
    System.out.println(service.sayHello("consumer"));
}
```

提供者侧：

```java
RpcProviderBootstrap.fromConfig()
        .registerService(HelloService.class, new HelloServiceImpl())
        .start();
```

### 3. 传输抽象层

当前已经明确为：

- `RpcTransport`：客户端传输抽象
- `RpcServer`：服务端启动抽象
- `RpcTransportFactory`：客户端传输实现选择
- `RpcServerFactory`：服务端传输实现选择
- `TransportType`：传输类型枚举

好处：

- `netty` / `socket` 的选择只在工厂层决策
- `RpcProxyFactory`、示例启动类、外部调用方都不再直接依赖具体传输实现
- 后续扩展新的传输类型时，新增实现并接到工厂即可

### 4. 注册中心创建层

新增：

- `com.rpc.registry.RegistryType`
- `com.rpc.registry.factory.ServiceRegistryFactory`

职责：

- 将注册中心选择与构建从启动代码中剥离
- 把原来示例/测试中直接 `new ZooKeeperRegistryImpl(...)` 的行为收口

目前工厂默认支持 `zookeeper`，后续如果引入 `nacos`、`etcd`、`in-memory`，只需要扩展该工厂。

### 5. 监控/统计层

为避免 `registry` 层直接依赖 `transport.netty.server.statistics` 包，新增：

- `com.rpc.metrics.ServiceMetrics`
- `com.rpc.metrics.ServiceMetricsManager`

并将：

- `LocalRegistryImpl`
- `RpcRequestExecutor`

从直接依赖 netty 统计实现，改为依赖中立的 metrics 层。

这一步的意义是：

- `registry` 与 `dispatch` 不再知道 netty 统计包的存在
- 统计职责从具体传输实现中抽离
- 层次关系更明确

## SPI 如何用于配置

当前项目中真正适合作为 SPI 的，是“可替换算法与组件能力”，而不是“带运行时参数的实例”。

因此本次重构采用如下策略：

### 1. 继续使用 SPI 的部分

- `Serializer`
- `LoadBalancer`

它们本身具备：

- 统一接口
- 多个实现
- 无需复杂构造参数

配置文件通过名字驱动 SPI：

```properties
rpc.serializer=kryo
rpc.loadbalancer=random
```

消费者启动时：

- `RpcConfigLoader` 读取字符串配置
- `RpcConsumerBootstrap` 将配置映射为 `RpcClientConfig`
- `LoadBalancerFactory` / `SerializerFactory` 通过 SPI 名称解析具体实现

这就实现了“SPI + 配置文件”的组合。

### 2. 为什么注册中心没有直接走当前的通用 SPI Loader

`ExtensionLoader` 当前要求扩展类可以通过无参构造创建。

但像 `ZooKeeperRegistryImpl` 这类组件需要：

- 连接地址
- session timeout

这类“带运行时参数的对象”更适合：

- 配置对象 + 工厂
- 或者更完整的 provider SPI（例如 `RegistryProvider#create(config)`）

本次先落地工厂方案，避免为了 SPI 而强行 SPI，保持实现简单可靠。

## 配置文件化后的默认配置

已新增并扩展 `rpc-core/src/main/resources/rpc.properties`，包括：

- 传输层：`rpc.transport`
- 序列化：`rpc.serializer`
- 负载均衡：`rpc.loadbalancer`
- 注册中心：`rpc.registry.type` / `rpc.registry.address`
- provider 监听地址与线程：`rpc.server.*`
- consumer 超时、心跳、重试：`rpc.client.*`

示例：

```properties
rpc.transport=netty
rpc.serializer=kryo
rpc.loadbalancer=random
rpc.registry.type=zookeeper
rpc.registry.address=127.0.0.1:2181
rpc.server.host=127.0.0.1
rpc.server.port=8080
rpc.client.connectTimeout=5000
rpc.client.readTimeout=10000
```

## 本次修复的硬编码与高风险点

### 1. 外部入口中的硬编码

原来示例代码中存在：

- 写死的 ZooKeeper 地址
- 写死的 host / port
- 写死的 transport 类型

现在改为统一走配置文件。

### 2. 代理处理器错误共享客户端实例

原来：

- `RpcInvocationHandler`
- `RpcMethodInterceptor`

内部持有的是 `static RpcTransport client`

这会导致：

- 多个代理共享同一个静态客户端
- 后续切换客户端实现或多实例运行时容易互相污染

现在改为实例字段，避免错误共享状态。

### 3. socket 编解码器写死默认序列化器

原来 `SocketMessageCodec` 固定使用默认序列化器，等于配置 `rpc.serializer` 对 socket 传输无效。

现在改为：

- 写消息时把 `serializerType` 写入流
- 读消息时根据流中的 `serializerType` 选择序列化器

这样 netty 与 socket 两种传输都能真正遵从配置。

### 4. Netty 客户端心跳时间单位错误

原来客户端把 `heartbeatInterval` 传给 `IdleStateHandler` 时使用了 `TimeUnit.SECONDS`，但配置值本身语义是毫秒。

现在已统一改为 `TimeUnit.MILLISECONDS`。

### 5. builder 默认值隐患

`RpcClientConfig` 中：

- `connectTimeout`
- `readTimeout`

已经显式标注 `@Builder.Default`，避免走 builder 时被置为 `0`。

## 当前建议的分层关系

建议按下面的依赖方向理解项目：

1. `config`
   负责配置模型、配置加载，不依赖 transport 实现细节
2. `bootstrap`
   负责外部入口编排，依赖 config/factory/proxy
3. `proxy`
   负责生成代理，依赖 `RpcTransport`
4. `transport`
   负责协议收发，不依赖外部启动代码
5. `registry`
   负责服务发现与注册，不感知具体 UI/示例入口
6. `metrics`
   负责统计，不依赖具体传输实现
7. `serialize` / `loadbalance`
   作为算法扩展点，通过 SPI 被配置驱动

依赖方向应尽量保持为：

`bootstrap -> factory -> transport/registry`

而不是：

`registry -> transport.netty.*`

## 对外使用方式建议

### 消费者

1. 在业务工程 classpath 放置 `rpc.properties`
2. 调用 `RpcConsumerBootstrap.fromConfig()`
3. 使用 `getService()` 获取远程代理

### 提供者

1. 在业务工程 classpath 放置 `rpc.properties`
2. 调用 `RpcProviderBootstrap.fromConfig()`
3. 使用 `registerService()` 注册接口与实现
4. `start()`

这样可以把原来的“需要提前拼很多对象”的接入方式，收敛成两三个步骤。

## 后续还可以继续优化的方向

### 1. 为注册中心引入 provider SPI

可以进一步抽象为：

- `RegistryProvider`
- `RegistryProviderFactory`

让 `zookeeper` / `nacos` / `etcd` 都变成统一 provider。

### 2. 把 netty/server 目录下的 `config` 包上移

当前 `RpcServerConfig` 仍位于 `transport.netty.server.config`，从命名上看仍偏向 netty。

后续可以迁移到更中立的位置，例如：

- `com.rpc.config.RpcServerConfig`

这样 `socket` 服务端就不会复用一个“看起来属于 netty”的配置类。

### 3. 统一测试基建

当前部分老测试仍直接依赖固定 ZooKeeper 地址。

建议后续统一改成：

- 测试专用 in-memory registry
- 或 testcontainers / embedded zk

这样测试可移植性更好。

### 4. 清理遗留包与命名

当前仓库里还存在一些历史性的包路径与命名问题，例如：

- netty statistics 包仍然保留
- 个别资源文件中有 `defalut` 这样的拼写遗留
- 文档与示例中仍有旧的写法

建议后续做一次纯清理型提交，专门处理命名统一与历史遗留。

## 本次调整后的收益

1. 外部接入代码显著减少。
2. transport/registry/proxy/bootstrap 的职责边界更清楚。
3. 序列化器与负载均衡真正实现了“SPI + 配置文件驱动”。
4. 示例与默认配置不再依赖写死地址。
5. 几个会导致后续配置失效或行为异常的隐藏问题已被修复。
6. Netty 客户端重连策略已经配置化，不再写死在处理器内部。

## 补充：重连参数配置化

当前 Netty 客户端支持通过配置文件控制重连行为：

```properties
rpc.client.reconnect.enabled=true
rpc.client.reconnect.maxRetryTimes=5
rpc.client.reconnect.initialDelaySeconds=2
rpc.client.reconnect.maxDelaySeconds=60
rpc.client.reconnect.jitter.enabled=true
rpc.client.reconnect.jitter.minSeconds=0
rpc.client.reconnect.jitter.maxSeconds=1
```

对应配置流转链路是：

`rpc.properties -> RpcConfigLoader -> RpcFrameworkConfig -> RpcClientConfig -> ReconnectHandler`

这样后续调整是否自动重连、重试次数、指数退避窗口和随机抖动范围时，不需要再改 `ReconnectHandler` 源码。
