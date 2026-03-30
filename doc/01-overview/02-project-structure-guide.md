# rpc-core 包结构说明

## 1. 这次整理的目标

这次整理只针对 `rpc-core` 的包结构，目标不是继续加功能，而是先把代码阅读入口理顺。

整理后的原则是：

1. 先按“框架层级”分包，而不是按“功能点随手堆在同级目录”分包。
2. 看到包名就能大致判断它在调用链中的位置。
3. 上层关注编排和入口，下层关注协议、传输和基础设施。
4. 把扩展点、治理能力、运行时能力单独放出来，避免和主链路混在一起。

## 2. rpc-core 当前顶层包

`rpc-core/src/main/java/com/rpc/core` 目前按下面 12 个顶层包组织：

- `api`
- `common`
- `config`
- `discovery`
- `extension`
- `invoke`
- `observability`
- `protocol`
- `registry`
- `resilience`
- `runtime`
- `transport`

这 12 个包可以理解成从“对外入口”到“底层实现”的一条分层骨架。

## 3. 顶层包职责

### 3.1 `com.rpc.core.api`

这一层是框架对外的高层入口，主要解决“外部怎么接这个 RPC（远程过程调用）”。

- `annotation`
  - 放 `@RpcService`、`@RpcReference` 这类注解。
- `bootstrap`
  - 放 `RpcConsumerBootstrap`、`RpcProviderBootstrap`。
  - 负责把配置、注册中心、传输层、代理工厂组装起来。
- `scanner`
  - 负责注解扫描和自动注册。

阅读时可以把这一层理解成“框架入口层”。

### 3.2 `com.rpc.core.config`

这一层专门放配置模型和配置加载逻辑。

- `RpcFrameworkConfig`
- `RpcClientConfig`
- `RpcConfigLoader`
- 配置 key（键）、方法级配置解析

它的职责是“把外部配置变成框架内部能用的对象”，不应该掺杂调用逻辑。

### 3.3 `com.rpc.core.invoke`

这一层是调用编排层，是整个 consumer（消费端）主链路的核心。

- `proxy`
  - 动态代理和代理工厂。
- `invocation`
  - 调用选项、方法级配置、请求级参数。
- `cluster`
  - fail-fast（快速失败）、fail-over（失败切换）这类集群容错策略。
- `filter`
  - consumer（消费端）/provider（提供端）/invoker（调用执行阶段）三段 filter（过滤器）链。
- `context`
  - `RpcContext` 和调用上下文透传。
- `async`
  - 异步调用结果和异步请求管理。

这一层解决的是“一个方法调用如何被编排成一次 RPC（远程过程调用）请求”。

### 3.4 `com.rpc.core.discovery`

这一层是消费端视角的服务发现层。

- 服务订阅
- 本地缓存
- 服务目录
- 地址快照

它和 `registry` 的区别是：
- `registry`（注册）更偏注册中心能力本身
- `discovery`（发现）更偏 consumer（消费端）如何使用服务列表

### 3.5 `com.rpc.core.registry`

这一层是注册中心抽象和实现。

- 服务注册
- 服务下线
- 本地注册表
- ZooKeeper（分布式协调组件）注册实现

可以把它理解成“provider（提供端）/consumer（消费端）与注册中心交互的基础设施层”。

### 3.6 `com.rpc.core.transport`

这一层是网络传输层，只关心“请求如何发出去、响应如何收回来”。

- `transport.netty`
  - Netty（网络通信框架）客户端、服务端、连接池、handler（处理器）。
- `transport.socket`
  - Socket（套接字）客户端、服务端。
- `transport.factory`
  - 按配置切换 netty（网络通信框架）/socket（套接字）。

这一层不应该承载太多治理逻辑，重点是连接、收发、编解码挂载。

### 3.7 `com.rpc.core.protocol`

这一层是协议层。

- 消息头
- 请求/响应模型
- 心跳模型
- 协议编解码

它负责定义“线上传输的到底是什么格式”。

### 3.8 `com.rpc.core.extension`

这一层是扩展点层。

- `serialize`
  - 序列化器 SPI（可插拔扩展点）
- `loadbalance`
  - 负载均衡 SPI（可插拔扩展点）
- `spi`
  - 扩展加载器、注入注解、初始化注解

后续如果再加压缩、鉴权、路由等扩展点，也应该优先进这一层。

### 3.9 `com.rpc.core.resilience`

这一层是容错与保护能力。

- 重试
- 限流
- 熔断
- 降级

这层的定位是“调用保护能力”，不应该散落到 transport（传输）或 proxy（代理）的细节实现里。

### 3.10 `com.rpc.core.observability`

这一层是可观测性。

- metrics（指标）
- 运行指标
- 服务指标

后续如果再加 tracing（链路追踪）、日志桥接，也应该继续放在这一层。

### 3.11 `com.rpc.core.runtime`

这一层主要放服务端运行时能力。

- 服务端生命周期
- 业务线程池
- in-flight（进行中）请求统计

它解决的是 provider（提供端）运行过程中的资源管理问题。

### 3.12 `com.rpc.core.common`

这一层放通用常量、异常、工具类。

这里应该尽量保持“公共基础设施”定位，不要让业务编排逻辑回流进来。

## 4. 按层级理解调用链

如果按阅读顺序看，当前 `rpc-core` 大致可以按下面这条链理解：

### 4.1 Consumer（消费端）侧

1. `api.bootstrap`
   - 加载配置，初始化 consumer（消费端）。
2. `invoke.proxy`
   - 生成代理对象。
3. `invoke.invocation / filter / cluster`
   - 处理方法级配置、过滤器、重试、熔断、降级。
4. `discovery`
   - 获取可用服务地址。
5. `transport`
   - 发请求、收响应。
6. `protocol`
   - 编解码消息。

### 4.2 Provider（提供端）侧

1. `api.bootstrap`
   - 启动 provider（提供端）。
2. `registry`
   - 注册服务。
3. `transport`
   - 接收请求。
4. `protocol`
   - 解码消息。
5. `invoke.filter`
   - provider（提供端）filter（过滤器）链处理。
6. `runtime`
   - 业务线程池和生命周期控制。

## 5. 为什么这种分层比之前更清晰

之前的问题是很多包都挤在同一层，读代码时很容易出现几个判断成本：

1. 这是入口层代码，还是底层实现代码？
2. 这是调用编排逻辑，还是传输细节？
3. 这是扩展点，还是具体实现？
4. 这是 provider（提供端）侧能力，还是 consumer（消费端）侧能力？

现在按层级拆开以后，判断成本明显下降：

- 看见 `api`，就知道这是框架入口。
- 看见 `invoke`，就知道这是调用编排。
- 看见 `transport`，就知道这是网络传输。
- 看见 `protocol`，就知道这是协议模型。
- 看见 `extension`，就知道这是 SPI（可插拔扩展点）。
- 看见 `resilience`，就知道这是限流/熔断/重试/降级。

也就是说，包名本身已经开始表达“它在项目中的结构位置”。

## 6. 推荐阅读顺序

如果是第一次读这个项目，建议按下面顺序看：

1. `com.rpc.core.api.bootstrap`
2. `com.rpc.core.config`
3. `com.rpc.core.invoke.proxy`
4. `com.rpc.core.invoke.invocation`
5. `com.rpc.core.invoke.filter`
6. `com.rpc.core.discovery`
7. `com.rpc.core.transport`
8. `com.rpc.core.protocol`
9. `com.rpc.core.registry`
10. `com.rpc.core.extension`
11. `com.rpc.core.resilience`
12. `com.rpc.core.runtime`

这样看会更符合“从入口到落地执行”的思路。

## 7. 当前结构下仍然建议继续收口的点

这次已经把 `rpc-core` 的大包层级拉开了，但后面还可以继续做：

1. `config` 里 `RpcConfigLoader` 仍然偏大，后面可以继续拆成多个 binder（绑定器）/parser（解析器）。
2. `transport.netty` 下面的 client（客户端）/server（服务端）子目录还可以继续压一轮职责边界。
3. `invoke.async` 现在已经独立出来，但和同步调用模型之间的接口还可以再统一。
4. `common` 要控制体积，避免以后再次变成“什么都往里放”的包。

这些属于下一轮优化，不影响当前“通过包结构快速理解项目”的目标。

## 8. 当前结论

这次整理之后，`rpc-core` 已经形成了比较清晰的层级结构：

- `api` 负责入口
- `config` 负责配置
- `invoke` 负责调用编排
- `discovery/registry` 负责服务寻址
- `transport/protocol` 负责网络与协议
- `extension` 负责扩展点
- `resilience/observability/runtime` 负责运行期能力
- `common` 负责公共基础设施

后面你再读代码时，可以先看包，再看类。只靠包结构，已经能大致判断一个类为什么存在、处在哪一层、应该和哪些层交互。
