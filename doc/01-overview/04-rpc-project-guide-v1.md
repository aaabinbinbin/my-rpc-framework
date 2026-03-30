# RPC 框架详细文档（第一版）

## 1. 文档目的

这份文档不是简单的“怎么运行项目”，而是尽量把当前这个 RPC 框架的整体结构、核心调用链、每一层职责、关键设计取舍、扩展点和当前能力边界讲清楚。

这份文档适合这几类阅读场景：

1. 你想快速建立对整个项目的整体认识。
2. 你准备逐步精读源码，但希望先知道从哪里开始看。
3. 你后续要继续重构、优化、加功能，需要先明确当前骨架已经收到了什么程度。
4. 你需要整理项目亮点、准备面试或项目说明材料。

当前这份是第一版，重点是把“骨架和主链路”讲清楚。后续如果继续完善，可以再拆成：

1. 消费端调用链详解
2. 服务端处理链详解
3. 配置系统详解
4. SPI 与扩展机制详解
5. 容错治理详解
6. Spring / Spring Boot 接入详解

---

## 2. 项目当前目标

这个项目当前阶段的目标，不是立刻追求极致性能，也不是一上来就把所有高级能力堆满，而是先把框架骨架搭清楚，做到下面几件事：

1. 分层清晰，代码阅读入口明确。
2. 消费端、服务端主链路可追踪。
3. 配置入口统一，不靠大量手写组装。
4. 传输、序列化、负载均衡等能力可替换。
5. 限流、熔断、降级、重试等基础治理能力有统一落点。
6. Spring / Spring Boot 场景下的接入方式尽量自然。

你可以把当前项目理解成：

- 一个“已经具备清晰骨架”的教学型 RPC 框架
- 同时也是一个“还可以继续演进”的实验型框架

它不是要直接对标 Dubbo、gRPC 的成熟度，而是参考这些成熟框架的分层思路，把自己的结构先整理到“能继续长”的状态。

---

## 3. 模块结构

当前项目模块可以按职责分成四层。

### 3.1 核心模块

- `rpc-core`

这是框架核心，绝大多数能力都在这里，包括：

- 配置模型
- 协议模型
- 传输层
- 注册中心与服务发现
- 调用编排
- 动态代理
- SPI 扩展
- 限流/熔断/降级/重试
- 运行时与可观测性

### 3.2 接入层模块

- `rpc-spring`
- `rpc-spring-boot-starter`

这两层的作用是降低外部使用成本。

`rpc-spring` 负责把：

- `@RpcService`
- `@RpcReference`

接入 Spring 容器生命周期。

`rpc-spring-boot-starter` 进一步把：

- 自动装配
- 包扫描
- `rpc.*` 配置绑定

接入 Spring Boot。

### 3.3 示例模块

- `example-api`
- `example-provider`
- `example-consumer`

这三个模块不是框架核心逻辑，而是示例工程：

- `example-api` 放接口
- `example-provider` 放服务提供者
- `example-consumer` 放服务消费者

当前这两个 example 已经改成 Spring Boot 风格，适合作为联调和展示入口。

### 3.4 文档模块

- `doc`

当前文档建议保留三类：

1. `current-usage-guide.md`
2. `project-structure-guide.md`
3. `rpc-roadmap.md`
4. 当前这份 `rpc-project-guide-v1.md`

其中这份文档负责最完整的项目说明。

---

## 4. rpc-core 包结构

当前 `rpc-core` 已经按层级整理到 `com.rpc.core` 下，核心顶层包如下：

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

这些包不是按“想到什么功能就放一起”的方式组织，而是按“在框架中的结构位置”组织。

### 4.1 api

这是对外入口层，主要放：

- 注解
- bootstrap
- 扫描器

这一层的职责是回答两个问题：

1. 外部怎么接入这个框架？
2. 框架启动时怎么把内部组件组装起来？

### 4.2 config

这是配置层，负责：

- 定义配置模型
- 读取配置
- 绑定配置
- 生成统一配置对象

这一层不负责真正发请求，也不负责真正执行业务，只负责把外部配置转换成框架内部能使用的对象。

### 4.3 invoke

这是调用编排层，是消费端最核心的一层。

它负责：

- 动态代理
- 调用选项解析
- 方法级配置覆盖
- filter 链
- cluster 策略
- `RpcContext`

如果说 `transport` 负责“怎么发”，那 `invoke` 负责“什么时候发、发到哪里、发之前和发之后做什么”。

### 4.4 discovery

这是服务发现层，更偏消费者视角。

它负责：

- 订阅服务
- 本地目录缓存
- 地址快照
- 预热
- stale fallback

### 4.5 registry

这是注册中心层，更偏基础设施视角。

它负责：

- 服务注册
- 服务下线
- 与 ZooKeeper 等注册中心交互
- 本地注册表

### 4.6 transport

这是网络传输层。

当前支持两种传输实现：

1. `netty`
2. `socket`

它只关心：

- 建连
- 发包
- 收包
- 编解码挂载
- 心跳
- 重连

### 4.7 protocol

这是协议模型层。

它定义：

- `RpcHeader`
- `RpcMessage`
- `RpcRequest`
- `RpcResponse`
- 心跳模型
- 协议编解码

### 4.8 extension

这是扩展点层。

目前主要包括：

- serializer SPI
- load balancer SPI
- SPI loader

这层的存在，是为了把“可替换能力”从主链路里抽出来。

### 4.9 resilience

这是容错治理层。

目前包括：

- retry
- circuit breaker
- degradation
- rate limit

### 4.10 observability

这是可观测性层。

目前包括：

- 服务指标
- 运行时指标
- MDC 配套能力

### 4.11 runtime

这是服务端运行时层。

目前主要放：

- 业务线程池
- 服务端生命周期控制

### 4.12 common

这是公共基础设施层。

包括：

- 常量
- 工具
- 公共异常

这一层需要控制体积，避免重新演变成“什么都往里放”的杂包。

---

## 5. 整体调用链概览

### 5.1 消费端主链路

消费端一次 RPC 调用，整体上会经过下面这些步骤：

1. 业务代码调用代理对象的方法
2. 动态代理把本地方法调用封装成 `RpcRequest`
3. `invoke` 层解析方法级配置，组装调用选项
4. 进入 filter 链
5. 做 trace、MDC、metrics、限流、熔断、降级等横切逻辑
6. 通过服务发现目录拿到服务实例列表
7. 通过负载均衡器选出目标实例
8. 通过 cluster 策略决定是否重试/失败转移
9. 交给 transport 层发请求
10. transport 收到响应后反序列化为 `RpcResponse`
11. 结果回到代理层，再返回给业务代码

### 5.2 服务端主链路

服务端一次请求处理，整体上会经过下面这些步骤：

1. 服务端接收网络请求
2. protocol 层把字节流还原成 `RpcMessage`
3. dispatcher 区分心跳与业务请求
4. 业务请求进入 provider filter 链
5. 做 provider 侧限流、MDC、metrics 等横切逻辑
6. 通过本地注册表找到服务实例
7. 通过反射调用具体方法
8. 得到结果后组装 `RpcResponse`
9. 通过 protocol 编码后回写给客户端

---

## 6. 配置系统

## 6.1 为什么单独做配置层

在重构前，配置使用和对象组装是耦合在一起的，外部使用时要手动创建很多对象：

- 注册中心
- 客户端配置
- transport
- proxy factory

这样的问题是：

1. 外部接入成本高
2. 代码重复
3. 配置优先级不清晰
4. 后续加参数很容易散

所以后来把配置系统单独收成一层。

### 6.2 当前配置入口

当前主要有三种配置入口：

1. `rpc.properties`
2. JVM `-D`
3. Spring Boot `application.yml / application.properties`

在非 Spring Boot 场景下，`RpcConfigLoader` 会负责读取配置。

在 Spring Boot 场景下，`RpcBootFrameworkProperties` 会先把 `rpc.*` 绑定成对象，再转为 `RpcFrameworkConfig`。

### 6.3 核心配置对象

当前主要配置对象是：

- `RpcFrameworkConfig`
- `RpcClientConfig`
- `RpcServerConfig`

其中：

- `RpcFrameworkConfig` 是高层统一配置
- `RpcClientConfig` 是 client transport 和 invocation 层真正用到的配置
- `RpcServerConfig` 是 server transport 层真正用到的配置

### 6.4 为什么要把 RpcConfigLoader 拆层

后续已经把 `RpcConfigLoader` 从“超大解析器”拆成了：

- `RpcPropertySource`
- `RpcFrameworkConfigBinder`
- `RpcClientConfigBinder`
- `RpcServerConfigBinder`
- `RpcFilterConfigBinder`
- `RpcMethodConfigBinder`

这样做的原因是：

1. 读取配置和绑定配置是两件事
2. client/server/filter/method 的配置职责不同
3. 以后新增配置，不需要继续把解析逻辑堆到一个类里

---

## 7. 服务注册与服务发现

### 7.1 为什么区分 registry 和 discovery

很多自写 RPC 项目会把“服务注册”和“服务发现”混成同一件事，但它们其实不是同一个层次的职责。

注册中心层更关心：

- 往哪里注册
- 怎么下线
- watcher 怎么挂

服务发现层更关心：

- consumer 怎么拿地址
- 如何做本地缓存
- 注册中心失败时怎么兜底

所以当前拆成了：

- `registry`
- `discovery`

### 7.2 LocalRegistry

`LocalRegistryImpl` 的作用是保存 provider 当前进程里的服务实例对象。

它和注册中心的关系是：

- `LocalRegistryImpl` 保存“对象实例”
- `ServiceRegistry` 保存“地址信息”

这是两个不同层次。

服务端真正反射调用时，需要的是对象实例，所以不能只依赖 ZooKeeper 上的地址。

### 7.3 ZooKeeperRegistryImpl

当前 ZooKeeper 这一层同时实现了：

- `ServiceRegistry`
- `ServiceDiscovery`

它当前的节点结构是：

`/rpc/{serviceName}/{host-port}`

其中 provider 实例节点使用临时节点，原因是：

1. provider 宕机后不需要等待手动摘除
2. ZooKeeper session 失效后节点会自动消失
3. consumer 不容易拿到僵尸地址

### 7.4 ServiceDirectory

`ServiceDirectory` 是消费端真正用来管理服务实例目录的对象。

它负责：

- 懒订阅
- 地址缓存
- TTL
- stale fallback
- 预热

这个对象的存在很关键，因为它把“注册中心变化监听”和“consumer 实际调用目录”隔开了。

---

## 8. 动态代理与调用编排

### 8.1 为什么一定要有代理层

RPC 的本质，就是把本地方法调用转换成远程调用。

所以代理层要做的事情是：

1. 拿到调用的方法名、参数、参数类型
2. 组装成 `RpcRequest`
3. 发起远程请求
4. 把结果再还原成调用结果

当前这层主要由：

- `RpcProxyFactory`
- `RpcInvocationHandler`
- `RpcMethodInterceptor`

负责。

### 8.2 JDK 动态代理和 CGLIB

当前策略是：

- 如果目标是接口，用 JDK 动态代理
- 如果目标是具体类，用 CGLIB

这样做是为了兼容两种使用方式。

### 8.3 为什么要把 ProxyFactory 改成实例化主路径

之前是静态全局 client，这会带来问题：

1. 多个 bootstrap 共用状态
2. 测试隔离差
3. 后续并行实例不自然

所以现在主路径已经改成：

- `RpcProxyFactory.create(rpcTransport)`

静态入口只保留兼容用途。

---

## 9. Filter 链与 RpcContext

### 9.1 为什么要引入 filter 链

如果没有 filter 链，后续这些能力都会散到 transport、proxy 或 executor 里：

- trace
- metrics
- MDC
- 限流
- 熔断
- 降级

那后续会越来越难维护。

所以当前把横切逻辑统一收到了 filter 链。

### 9.2 当前 filter phase

目前 filter 分三段：

1. `CONSUMER`
2. `INVOKER`
3. `PROVIDER`

这三段的意义是：

- `CONSUMER`：请求刚从代理层出来
- `INVOKER`：即将真正进入 cluster/transport 调用
- `PROVIDER`：请求到达 provider 侧、反射调用之前

### 9.3 RpcContext

`RpcContext` 当前是线程绑定的调用上下文，主要承载：

- requestId
- traceId
- attachments

它的作用是给 filter 和日志上下文提供统一容器，而不是让这些信息散落在各层方法参数里。

### 9.4 当前内置 filter

当前比较核心的 filter 有：

- `TraceFilter`
- `MdcFilter`
- `ConsumerMetricsFilter`
- `ProviderMetricsFilter`
- `ConsumerCircuitBreakerFilter`
- `ProviderRateLimitFilter`
- `ProviderMdcFilter`

这些 filter 分别解决不同横切问题。

---

## 10. Cluster、重试、限流、熔断、降级

### 10.1 为什么把治理层从 transport 中抽出来

transport 只该解决网络问题，例如：

- 连接
- 发包
- 收包
- 心跳
- 重连

但下面这些其实是调用治理问题：

- 重试
- 熔断
- 降级
- 限流

所以当前这些能力被收到了：

- `invoke`
- `resilience`
- `filter`

### 10.2 Retry

当前 `RetryExecutor` 做的是请求级重试。

注意它和连接重连不是一回事：

- 重连：恢复网络连接
- 重试：重新执行一次 RPC 请求

### 10.3 Circuit Breaker

当前熔断支持：

- 服务级
- 方法级

`CircuitBreakerImpl` 当前状态机是：

1. `CLOSED`
2. `OPEN`
3. `HALF_OPEN`

它的逻辑是典型的熔断器模型：

- 失败率达到阈值时打开
- 等待窗口结束后进入半开
- 半开探测成功后关闭
- 半开探测失败后重新打开

### 10.4 Degradation

当前支持的降级策略有：

1. `failFast`
2. `defaultValue`

目前 consumer 和 provider 两侧都支持按配置选择。

### 10.5 Rate Limit

当前限流器使用固定窗口实现。

它的好处是：

1. 实现简单
2. 易于理解
3. 适合当前阶段的骨架建设

它不是最精细的限流算法，但对这个项目当前阶段已经足够。

---

## 11. 协议层与序列化

### 11.1 为什么统一协议模型

当前无论是 netty 还是 socket，都统一使用：

- `RpcHeader`
- `RpcMessage`
- `RpcRequest`
- `RpcResponse`

这样做的好处是：

1. transport 层可以替换
2. 调用层不依赖具体传输实现
3. 序列化器切换时影响范围小

### 11.2 RpcRequest

`RpcRequest` 本质上是“可跨进程传输的方法调用模型”，它包含：

- serviceName
- methodName
- parameterTypes
- parameters
- returnType
- attachments

其中 `attachments` 很重要，因为它允许：

- trace 透传
- 方法级超时覆盖
- 序列化器覆盖
- 其他元信息透传

### 11.3 RpcResponse

`RpcResponse` 当前是通用响应模型，包含：

- requestId
- code
- message
- data

这样 transport 和 proxy 层都不需要直接依赖业务返回类型。

### 11.4 为什么默认序列化器切到 protobuf

当前默认是 `protobuf`，原因不是“绝对最快”，而是综合权衡后更均衡：

1. 比 `java` 序列化更轻
2. 比 `json` 体积更小
3. 相比 `kryo`，协议语义更稳定
4. 更适合后续继续演进

当前实现里为了兼容现有模型，使用的是基于 Protostuff 的 protobuf 风格序列化。

---

## 12. 传输层

## 12.1 为什么同时保留 netty 和 socket

这不是为了重复造轮子，而是为了分层更清楚。

`socket` 版适合：

- 理解最小传输实现
- 做简单集成测试
- 作为对照实现

`netty` 版适合：

- 长连接
- 请求复用
- 心跳
- 重连
- 更接近真实 RPC 运行方式

### 12.2 netty 客户端

`RpcNettyClient` 现在是较完整的客户端实现，内部包含：

- `ConnectionPool`
- `RpcConnection`
- `RequestManager`
- `RpcClientHandler`
- `HeartbeatHandler`
- `ReconnectHandler`

这几部分配合起来完成：

1. 建立连接
2. 复用连接
3. 挂起请求 future
4. 收到响应后唤醒 future
5. 空闲时发心跳
6. 断连时重连

### 12.3 netty 服务端

`RpcNettyServer` 当前负责：

1. 启动 Netty 服务端
2. 构建 pipeline
3. 交给 `RpcRequestDispatcher`
4. 通过 `RpcRequestExecutor` 把业务请求投递到业务线程池
5. 配合 `ServerLifecycle` 实现优雅停机

### 12.4 socket 实现

`socket` 版已经尽量保持和 netty 版上层一致：

- 同样走 discovery
- 同样走 invocation executor
- 同样走方法级配置

差别只留在底层发包方式。

这也是当前整个重构的一个核心目标：上层复用，底层可替换。

---

## 13. SPI 扩展系统

### 13.1 为什么要做 SPI

如果不做 SPI，后续每增加一个序列化器或负载均衡器，都要改主链路代码。

这会导致：

1. 核心代码越来越膨胀
2. 配置无法自然驱动扩展
3. 项目无法体现可扩展骨架

所以当前扩展系统单独收成了：

- `@SPI`
- `ExtensionLoader`
- `ExtensionFactory`
- `@Inject`
- `@Initialize`

### 13.2 ExtensionLoader 负责什么

`ExtensionLoader` 负责：

1. 从 `META-INF/rpc/` 下读取扩展定义
2. 按名字找到实现类
3. 创建扩展实例
4. 做 `@Inject`
5. 调用 `@Initialize`
6. 缓存扩展实例

### 13.3 当前已接入的 SPI

目前最主要的 SPI 有：

1. `Serializer`
2. `LoadBalancer`
3. `RpcFilter`

这些已经足够支撑框架当前阶段的“可替换能力”。

---

## 14. Spring 与 Spring Boot 接入

### 14.1 rpc-spring

`rpc-spring` 的目标是把框架接入 Spring 生命周期。

主要组件是：

- `RpcSpringRegistrar`
- `RpcSpringManager`

其中：

- `RpcSpringRegistrar` 负责扫描 `@RpcService`
- `RpcSpringManager` 负责注入 `@RpcReference`，并在容器启动后真正发布服务

### 14.2 rpc-spring-boot-starter

starter 再往前走一步，把：

- 自动装配
- 包扫描
- 配置绑定

都接到 Spring Boot。

其中关键类包括：

- `RpcSpringBootAutoConfiguration`
- `RpcBootFrameworkProperties`

这样在 Spring Boot 场景下，就不需要再手动写 bootstrap 初始化代码。

---

## 15. 当前示例如何理解

当前 `example-provider` 和 `example-consumer` 的定位是：

- 框架最小可运行示例
- Spring Boot 接入示例
- 联调入口

当前建议的体验方式是：

1. 启动 ZooKeeper
2. 启动 provider
3. 启动 consumer

这样就能看到：

- 服务注册
- 服务发现
- 代理调用
- 过滤器生效
- 指标记录

---

## 16. 当前代码阅读建议

如果你要系统读这个项目，建议按这个顺序。

### 第一阶段：建立整体骨架

1. `doc/current-usage-guide.md`
2. `doc/project-structure-guide.md`
3. 当前这份文档

### 第二阶段：先看入口

1. `RpcConsumerBootstrap`
2. `RpcProviderBootstrap`
3. `RpcSpringManager`
4. `RpcSpringBootAutoConfiguration`

### 第三阶段：看消费端主链路

1. `RpcProxyFactory`
2. `RpcInvocationHandler`
3. `RpcClientInvocationExecutor`
4. `RpcServiceResolver`
5. `ClusterInvoker`
6. `FilterManager`

### 第四阶段：看注册发现与传输

1. `ServiceDirectory`
2. `ZooKeeperRegistryImpl`
3. `RpcNettyClient`
4. `ConnectionPool`
5. `RequestManager`
6. `RpcNettyServer`
7. `RpcRequestDispatcher`
8. `RpcRequestExecutor`

### 第五阶段：看治理与扩展

1. `RetryExecutor`
2. `CircuitBreakerImpl`
3. `RateLimiterManager`
4. `DegradationPolicyFactory`
5. `ExtensionLoader`
6. `SerializerFactory`
7. `LoadBalancerFactory`

---

## 17. 当前设计取舍

### 17.1 为什么先做骨架，不先做极致优化

因为当前项目最主要的问题不是“某个点还不够快”，而是“如果不先收骨架，后面越改越乱”。

所以当前优先级是：

1. 分层
2. 可读性
3. 统一入口
4. 扩展点
5. 治理能力落点

而不是：

1. 极限性能
2. 所有高级特性一次做完

### 17.2 为什么还保留 socket

保留 `socket` 不是为了和 `netty` 重复，而是为了：

1. 提供简单实现对照
2. 提供简单测试路径
3. 证明上层编排和底层 transport 已经分离

### 17.3 为什么 filter 和治理能力要先统一

因为一旦 trace、metrics、熔断、限流、降级都散在不同层里，后续所有能力都会互相污染。

filter 链的存在，是整个项目能继续演进的关键之一。

---

## 18. 当前仍然存在的边界

当前这个项目虽然骨架已经清晰很多，但还不是“成熟生产框架”。

还没有重点展开的能力包括：

1. 压缩
2. 泛化调用
3. 异步调用模型深化
4. 更完整的 benchmark
5. 更复杂的 provider fallback
6. 更成熟的治理控制台或管理接口

这些不是当前阶段没价值，而是故意后置。

---

## 19. 当前阶段总结

如果从“项目是否已经具备一个清晰的 RPC 框架骨架”这个角度看，当前答案是：已经具备。

当前已经形成了比较明确的结构：

1. `config` 负责配置
2. `bootstrap` 负责组装入口
3. `invoke` 负责调用编排
4. `registry/discovery` 负责服务寻址
5. `transport/protocol` 负责网络和消息
6. `extension` 负责扩展点
7. `resilience` 负责容错治理
8. `observability/runtime` 负责运行时能力
9. `spring/starter` 负责接入体验

对你后续最重要的价值是两点：

1. 现在已经可以比较顺畅地读懂代码了
2. 后面再写更细的专题文档时，已经有清晰落点了

---

## 20. 下一步文档建议

这份第一版详细文档完成后，下一步最适合继续写的不是再把它无限拉长，而是拆专题。

建议下一批文档按下面顺序补：

1. `rpc-consumer-call-chain.md`
2. `rpc-provider-call-chain.md`
3. `rpc-config-system.md`
4. `rpc-spi-and-extension.md`
5. `rpc-resilience-design.md`
6. `rpc-spring-integration.md`

这样你后面无论是自己学习、继续重构，还是整理简历项目说明，都会更方便。
