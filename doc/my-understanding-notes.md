# 我对这个 RPC 项目的理解笔记

这份文档不是项目说明书，而是我在阅读源码、反复提问、修正理解之后形成的一份“面向自己”的理解笔记。

这份笔记的目标有两个：

1. 把当前项目的主线真正打通。
2. 把已经确认过的结论、容易混淆的点、已识别的问题和后续可优化的方向整理清楚。

整理原则：

- 以“当前项目真实实现”为准，不泛泛讲概念。
- 不把通用框架原理展开成教程，只讲它在这个项目里承担的角色。
- 保留细节，不为了追求简洁牺牲理解深度。

---

## 一、项目整体定位

这个项目从整体上看，是一个典型的：

- 自定义协议
- 注册中心
- 动态代理
- Netty 通信
- Spring 集成
- consumer / provider 双端治理

组成的 RPC 框架。

如果从职责上拆，我目前把它理解成下面几层：

1. Spring 集成层
   - 负责把 RPC 能力接入 Spring 容器生命周期。
   - 处理 `@RpcService` 和 `@RpcReference`。
2. consumer 调用入口层
   - 把本地接口方法调用翻译成 `RpcRequest`。
   - 执行 consumer 入口过滤链。
3. consumer 调用编排层
   - 解析方法级配置。
   - 做限流、熔断、cluster、retry。
   - 做服务发现和地址选择。
4. transport 层
   - 建连。
   - 发包。
   - future 回填。
5. 协议层
   - `RpcHeader`
   - `RpcMessage`
   - encoder / decoder
6. provider 执行层
   - 本地注册表。
   - provider 过滤链。
   - 反射调用真实服务对象。
7. 注册中心 / 服务发现层
   - provider 注册服务地址。
   - consumer 订阅服务列表变化。

如果把整个项目压成一句话，我目前的理解是：

consumer 侧通过代理对象拦截本地方法调用，把它翻译成 `RpcRequest`，经过 consumer 过滤链、调用编排、服务发现、负载均衡和 transport 发包后，provider 侧收到协议消息，解码并分发到本地真实服务对象执行，再把 `RpcResponse` 回传给 consumer，consumer 再通过 `requestId -> future` 的方式把结果还原回最初那个本地方法调用。

---

## 二、Spring 集成层

这部分的重点不是“Spring 原理”本身，而是“当前项目如何借用 Spring 生命周期完成 RPC 集成”。

关键类主要有：

- `rpc-spring/src/main/java/com/rpc/spring/RpcSpringRegistrar.java`
- `rpc-spring/src/main/java/com/rpc/spring/RpcSpringManager.java`
- `rpc-spring/src/main/java/com/rpc/spring/annotation/EnableRpc.java`
- `rpc-spring-boot-starter/src/main/java/com/rpc/spring/boot/RpcSpringBootAutoConfiguration.java`

### 2.1 `@RpcService` 和 `@RpcReference` 的处理路径完全不同

这两个注解虽然都属于 RPC 注解，但在 Spring 场景下，处理路径完全不同。

#### `@RpcService`

它标记的是服务实现类。

它的处理路径是：

1. 先被扫描到。
2. 注册成 Spring `BeanDefinition`。
3. 后续由 Spring 正常实例化成 Bean。
4. Spring 容器启动完成后，再由 `RpcSpringManager.start()` 把这些 Bean 发布成 RPC 服务。

所以：

- `@RpcService` 对应的对象，本质上仍然是 Spring Bean。
- 不是 RPC 框架跳过 Spring 自己 new 出来的对象。

#### `@RpcReference`

它标记的是字段，不是类。

它的处理路径是：

1. 字段所在的 Bean 仍然由 Spring 正常创建。
2. 在 Bean 初始化前，`RpcSpringManager` 扫描到字段上的 `@RpcReference`。
3. 调 `RpcConsumerBootstrap.getService(...)` 创建代理对象。
4. 用反射把这个代理对象写进字段。

所以：

- `@RpcReference` 注入进去的是 RPC 代理对象。
- 不是 provider 真实服务对象。
- 也不是 Spring 容器中现成某个 Bean 的别名。

### 2.2 `RpcSpringRegistrar` 到底做了什么

`RpcSpringRegistrar` 的本质职责不是实例化 Bean，而是：

- 在 Spring 还没有正式创建 Bean 之前
- 先往容器里注册 `BeanDefinition`

它主要做两件事：

1. 注册 `RpcSpringManager` 的 `BeanDefinition`。
2. 扫描 `@RpcService` 类，并把它们注册成 Spring `BeanDefinition`。

所以它更准确的定位是：

- 元数据注册器
- 不是对象创建器

这也意味着：

- 它注册的不是 Bean 对象。
- 而是 BeanDefinition。
- 后面 `RpcSpringManager` 最终仍然会被 Spring 实例化成真正的 Bean。

### 2.3 `RpcSpringManager` 为什么要实现这么多 Spring 接口

它实现多个 Spring 接口不是复杂化，而是因为它必须卡住多个生命周期阶段。

它实现的接口和意义：

- `BeanPostProcessor`
  - 为 `@RpcReference` 注入代理对象。
- `SmartLifecycle`
  - 在容器启动完成后发布 `@RpcService`。
- `ApplicationContextAware`
  - 拿到容器引用，方便查找 Bean。
- `DisposableBean`
  - 容器关闭时清理 bootstrap。
- `PriorityOrdered`
  - 让 BeanPostProcessor 尽量早执行。

所以它不是“炫技式实现很多接口”，而是因为它要同时插入：

1. Bean 创建阶段。
2. 容器启动阶段。
3. 容器销毁阶段。

### 2.4 `postProcessBeforeInitialization(...)` 插在 Spring 生命周期哪里

这个方法执行的位置是：

- Bean 已经实例化。
- 依赖注入基本完成。
- 初始化逻辑尚未执行。

也就是说顺序大致是：

1. 根据 `BeanDefinition` 实例化 Bean。
2. 做依赖注入。
3. 回调 `Aware`。
4. 执行 `postProcessBeforeInitialization(...)`。
5. 执行 `@PostConstruct` / `afterPropertiesSet()` / init-method。
6. 执行 `postProcessAfterInitialization(...)`。

所以 `@RpcReference` 注入发生在：

- 初始化逻辑之前。

这也解释了为什么业务 Bean 的 `@PostConstruct` 里通常已经能拿到代理对象。

### 2.5 `RpcSpringManager.start()` 做了什么

它的目标不是创建 Bean，而是：

- 找出容器里已经存在的 `@RpcService` Bean。
- 把它们发布成真正可远程访问的 RPC 服务。

它的主线是：

1. 先判断当前是否已经启动过，避免重复发布。
2. 从 `ApplicationContext` 中找出所有带 `@RpcService` 的 Bean 名称。
3. 如果当前容器里真的存在这些 Bean：
   - 获取 `RpcProviderBootstrap`。
   - 逐个从容器中拿到真实 Bean。
   - 读取其 `@RpcService` 注解。
   - 解析对外暴露的接口。
   - 调 `bootstrap.registerService(接口, 真实Bean)`。
4. 所有服务注册完之后，调用 `bootstrap.start()` 启动 provider。
5. 最后把 `running` 标记为 `true`。

这里最重要的结论是：

- 发布到 provider 本地注册表中的，是 Spring 真实 Bean。
- 不是 provider 代理对象。

### 2.6 为什么 Spring 集成层要接管注解处理，而不是继续依赖 `rpc-core` 自动扫描

这是当前项目里一个非常关键的边界问题。

`rpc-core` 本身也有注解处理能力，比如：

- `RpcProviderBootstrap.registerAnnotatedServices(...)`
- `RpcConsumerBootstrap.injectReferences(...)`

这些能力在“非 Spring 场景”下没有问题，因为对象生命周期本来就由框架自己控制。

但进入 Spring 场景之后，对象生命周期已经属于 Spring：

- 对象由 Spring 创建。
- 依赖由 Spring 注入。
- AOP、事务、生命周期回调都由 Spring 接管。

如果还让 `rpc-core` 自己扫描并实例化 `@RpcService`，会出现：

1. 重复创建对象。
2. Spring 依赖注入失效。
3. AOP / 事务增强失效。
4. 框架发布出去的对象和容器里的真实对象不一致。

如果还让 `rpc-core` 自己处理 `@RpcReference`，也会有问题，因为：

- 它无法掌控 Spring Bean 的正确生命周期时机。
- 它只能处理普通对象，不能天然接入 Spring Bean 初始化流程。

所以在 Spring 场景下，最合理的边界是：

- `rpc-core` 负责“怎么创建代理、怎么发布服务、怎么发请求、怎么处理请求”。
- `rpc-spring` 负责“在 Spring 里，对哪些对象、在什么时候调用这些能力”。

这也解释了为什么 Spring 集成层会显式关闭 core 层自动注解注册逻辑：

- Spring 管理对象来源。
- RPC 只复用这些对象做发布。

### 2.7 Spring Boot starter 和 `@EnableRpc` 是两条接入路径

当前项目里 Spring 接入其实有两条路：

1. `rpc-spring`
   - 显式写 `@EnableRpc`
   - 走 `RpcSpringRegistrar`
2. `rpc-spring-boot-starter`
   - 走自动配置
   - 根据 starter 配置注册 `RpcSpringManager`

这里还要记住一个细节：

- starter 场景下，`@RpcService` 扫描依赖 `rpc.spring.scan-packages`。
- 如果包配置不对，服务可能根本没注册进 Spring。

### 2.8 目前对 Spring 集成层的结论

就当前项目理解来说，Spring 集成层的主干已经够用了。最关键的点是：

1. `RpcSpringRegistrar` 负责注册 BeanDefinition。
2. `RpcSpringManager` 负责接 Spring 生命周期。
3. `@RpcService` 最终发布的是 Spring 真实 Bean。
4. `@RpcReference` 注入的是 RPC 代理对象。
5. Spring 场景下必须由集成层接管注解处理，不能继续让 core 自动扫描实例化。

---

## 三、Provider 启动与服务发布

provider 侧关键类主要有：

- `rpc-core/src/main/java/com/rpc/core/api/bootstrap/RpcProviderBootstrap.java`
- `rpc-core/src/main/java/com/rpc/core/registry/local/LocalRegistryImpl.java`
- `rpc-core/src/main/java/com/rpc/core/transport/netty/server/RpcNettyServer.java`

### 3.1 `RpcProviderBootstrap` 的定位

它不是“处理某次请求”的类，而是 provider 启动期总装配器。

它主要负责三件事：

1. 组装 provider 运行环境。
2. 注册服务。
3. 启动服务端。

更细一点说，它会准备：

- 注册中心客户端
- provider 过滤器运行环境
- 底层 `RpcServer`
- 本地注册表
- provider 侧服务端运行参数

### 3.2 `fromConfig(...)` 做了什么

这个方法是 provider 启动装配入口。

主线是：

1. 创建 provider 侧降级策略。
2. 配置 `FilterManager`。
3. 配置 provider 过滤器运行时参数。
4. 创建注册中心客户端 `ServiceRegistry`。
5. 组装 `RpcServerConfig`。
6. 创建真正的 `RpcServer`。
7. 返回 `RpcProviderBootstrap`。

所以 `fromConfig(...)` 的本质是：

- 把 provider 运行期所需基础设施全部装起来。
- 但这时还没有真正监听端口。

### 3.3 `registerService(...)` 做了什么

核心代码虽然很短：

```java
rpcServer.getLocalRegistry().register(serviceInterface.getName(), serviceImpl);
```

但它意义很大。

它做的是：

- 把 `接口全限定名 -> 真实服务对象` 注册到 provider 本地注册表。

这一步最重要的结论是：

- 对外暴露服务时使用的是接口名。
- 本地真实执行对象是实现类实例。

这和 consumer 构造 `RpcRequest` 时使用的：

- `serviceName = serviceClass.getName()`

正好对应上。

### 3.4 `LocalRegistryImpl.register(...)` 做了什么

它真正完成了两种注册：

1. 注册到本地注册表：
   - `serviceName -> serviceInstance`
2. 注册到外部注册中心：
   - `serviceName -> host:port`

这里一定要严格区分：

#### 本地注册表

用途：

- provider 收到请求后，按 `serviceName` 找到真实服务对象。

#### 注册中心

用途：

- consumer 做服务发现，找到有哪些 provider 地址可调。

所以这两个“注册”不是一类数据：

- 本地注册表存对象。
- 注册中心存地址。

### 3.5 `RpcProviderBootstrap.start()` 做了什么

在 Spring 场景下，它的核心几乎可以直接理解为：

- 启动底层 `RpcNettyServer`

也就是说：

1. 服务对象已经注册进本地注册表了。
2. 现在开始真正监听端口，对外提供 RPC 能力。

---

## 四、Provider 网络启动与执行链

### 4.1 `RpcNettyServer` 构造时准备了什么

`RpcNettyServer` 构造时就已经把 provider 运行时最关键的几块东西准备好了：

1. `LocalRegistry`
2. `ServerLifecycle`
3. `bizExecutor`
4. `requestProcessor`

它们分别代表：

- `LocalRegistry`
  - 本地服务分发表。
- `ServerLifecycle`
  - 记录当前是否还能接收新请求、当前 inflight 请求数。
- `bizExecutor`
  - 业务线程池。
- `requestProcessor`
  - provider 请求处理入口，内部由 `RpcRequestDispatcher` 和 `RpcRequestExecutor` 组成。

### 4.2 `RpcNettyServer.start()` 做了什么

它的主线是：

1. 创建 boss / worker 线程组。
2. 配置 Netty `ServerBootstrap`。
3. 配置 provider 侧 pipeline。
4. 绑定端口开始监听。

线程分工是：

- boss：接收新连接。
- worker：处理连接上的 IO 事件。
- biz 线程池：执行真实业务方法。

### 4.3 provider pipeline 有哪些 handler

当前主要是：

1. `IdleStateHandler`
2. `ServerHeartbeatHandler`
3. `RpcProtocolDecoder`
4. `RpcProtocolEncoder`
5. `RpcRequestHandler`

它们分别承担：

- 连接空闲检测。
- 连接层心跳 / 空闲处理。
- 字节流解码为 `RpcMessage`。
- `RpcMessage` 编码回字节流。
- Netty 层到框架请求处理层的桥接。

### 4.4 `RpcRequestHandler` 和 `RpcRequestDispatcher`

`RpcRequestHandler` 不是业务执行器，它只负责：

1. 拿到已经解码好的 `RpcMessage`。
2. 调用 `requestProcessor.process(...)`。
3. 如果有响应，再写回 channel。

而 `RpcRequestDispatcher` 才是 provider 侧的消息分流器。

它按 `messageType` 做分发：

- `HEARTBEAT_REQUEST`
  - 直接返回心跳响应。
- `REQUEST`
  - 交给 `RpcRequestExecutor`。

所以 provider 在真正执行业务请求前，还会先经过一层：

- 消息级分流。

### 4.5 provider 优雅停机入口控制

`RpcRequestDispatcher` 在真正处理请求前会先判断：

- 当前服务端是否还接收新请求。

如果服务端正在优雅停机，它会直接返回：

- `503 Server is shutting down`

所以 provider 停机不是简单硬停，而是：

1. 先拒绝新请求。
2. 再让已有 inflight 请求尽量跑完。

---

## 五、Provider 业务执行主线

### 5.1 `RpcRequestExecutor.execute(...)` 的职责

它才是 provider 真正的业务执行器。

它最外层做的事情是：

1. `inflight + 1`
2. 把真正的执行逻辑扔进 biz 线程池。
3. `finally inflight - 1`

这说明当前项目的 provider 模型是：

- 底层 Netty 网络处理异步。
- 业务执行下沉到独立线程池。
- 当前请求仍然以同步方式等待业务执行完成。

### 5.2 为什么 provider 要有业务线程池

因为 Netty worker 线程不能直接执行真实业务方法。

如果直接执行，会导致：

- 慢查询拖住 IO 线程。
- 阻塞调用拖住 IO 线程。
- 新请求收包、解码、回包都受影响。

所以业务线程池的意义是：

1. 隔离网络 IO 和业务执行。
2. 保护 Netty 线程。
3. 给 provider 提供并发控制能力。

它不是延迟队列，而是一个普通受限线程池：

- 核心线程数
- 最大线程数
- 阻塞队列容量

### 5.3 `invoke(rpcRequest)` 的主线

这个方法才真正进入 provider 业务执行主线。

它主要做：

1. 恢复 provider 侧 `RpcContext`。
2. 从本地注册表查真实服务对象。
3. 构造 provider 过滤链上下文。
4. 执行 `PROVIDER` 过滤链。
5. 过滤链终点反射调用目标方法。
6. 把结果包装成 `RpcResponse`。
7. `finally` 清理 `RpcContext`。

### 5.4 恢复 provider 侧 `RpcContext`

provider 收到请求后，会从 `RpcRequest` 恢复：

- `requestId`
- `traceId`
- attachments

恢复这些上下文的原因是：

1. provider MDC 要拿链路信息。
2. provider 过滤链要拿上下文。
3. 如果 provider 在当前线程里继续向下游发起 RPC，可以复用 `traceId`。

### 5.5 本地注册表真正的使用点

`localRegistry.getService(serviceName)` 就是本地注册表真正派上用场的地方。

也就是说，启动期建立的：

- `serviceName -> serviceInstance`

映射，在运行期请求真正到来时才被使用。

这一步本质上就是 provider 本地的服务分发。

### 5.6 provider 过滤链

在真实方法调用之前，provider 还会经过 `PROVIDER` 过滤链。

默认顺序是：

1. `providerRateLimit`
2. `providerMdc`
3. `providerMetrics`

也就是：

1. 先做准入保护。
2. 再准备日志上下文。
3. 再统计执行指标。
4. 最后才真正反射调用。

### 5.7 反射调用真实方法

框架最终会用：

- `methodName`
- `parameterTypes`
- `parameters`

去定位并执行目标方法。

其中 `parameterTypes` 很关键，因为 Java 方法支持重载，只靠 `methodName` 不够。

### 5.8 provider 结果收敛为 `RpcResponse`

业务方法执行完后，最终都要被收敛成：

- `RpcResponse`

两种情况：

1. provider 过滤链已经提前返回 `RpcResponse`
   - 比如限流失败。
   - 比如降级结果。
2. 真实业务方法只返回普通对象
   - 框架再统一包装成 `RpcResponse.success(...)`。

所以 consumer 最终不会直接收到裸业务对象，而是先收到 `RpcResponse`，再由代理层把其中的 `data` 还原出来。

---

## 六、Provider 侧治理：限流、降级、MDC、Metrics、线程池与停机

### 6.1 provider 限流和 provider 降级

当前 provider 限流入口在：

- `rpc-core/src/main/java/com/rpc/core/invoke/filter/impl/ProviderRateLimitFilter.java`

它按：

- `serviceName#methodName`

做方法级限流。

当前项目里真正的限流实现是：

- `RateLimiterManager`
- `FixedWindowRateLimiter`

所以当前 provider 限流本质上是：

- 单机
- 方法级
- 1 秒固定窗口
- 静态阈值

当拿不到令牌时：

1. 如果开启 provider 降级，则走降级策略。
2. 否则直接返回限流失败响应。

所以当前 provider 降级不是“所有异常都自动降级”，而主要是：

- provider 过载 / 限流后的兜底响应策略。

当前支持的降级策略主要是：

- `failFast`
- `defaultValue`

### 6.2 `RateLimiterManager` 在 provider 侧做什么

它不是具体限流算法实现，而是：

- 按 key 管理限流器实例。

它维护：

- `key -> RateLimiter`

真正做限流算法的是：

- `FixedWindowRateLimiter`

所以它更像一个：

- 限流器管理器 / 路由器。

### 6.3 当前 provider 限流的改进思路

当前实现简单，但还有明显改进空间：

1. 从固定窗口升级为更平滑的算法。
   - 滑动窗口
   - 令牌桶
2. 让限流和真实承压状态联动。
   - inflight
   - biz 线程池活跃数
   - 队列长度
3. 增加并发控制，不只看 QPS。
4. 支持更细粒度的方法级差异化配置。
5. 如果以后需要，再考虑分布式限流。

当前项目里如果要自然演进，我认为最合理的路径是：

- 保留现有“过滤器入口 + 配置 + 管理器 + 限流算法接口”结构。
- 再逐步把算法和策略扩起来。

### 6.4 provider MDC

provider 侧 MDC 过滤器是：

- `rpc-core/src/main/java/com/rpc/core/invoke/filter/impl/ProviderMdcFilter.java`

它从当前 `RpcContext` 和 `RpcRequest` 里取：

- `requestId`
- `traceId`
- `serviceName`
- `methodName`

写入日志 MDC。

它的作用不是业务逻辑，而是：

- 让 provider 当前请求执行期间打印的日志自动带上链路字段。

### 6.5 provider metrics

provider 侧 metrics 过滤器是：

- `rpc-core/src/main/java/com/rpc/core/invoke/filter/impl/ProviderMetricsFilter.java`

它按服务维度聚合：

- 总调用数
- 失败数
- 耗时
- 最近一次耗时

所以它不是“只统计当前请求”，而是：

- 每次请求都参与累计，最终沉淀成服务级指标。

### 6.6 停机等待时间 `shutdownTimeout`

这个不是请求超时，而是：

- provider 优雅停机时，最多等待多久让 inflight 请求尽量执行完。

它服务于：

1. 停止接收新请求。
2. 从注册中心摘除服务。
3. 等待在途请求执行完。
4. 再关闭线程池和网络资源。

所以它的意义是：

- 既不能简单硬停。
- 也不能无限等待。

---

## 七、Consumer 启动与代理创建

consumer 侧关键类主要有：

- `rpc-core/src/main/java/com/rpc/core/api/bootstrap/RpcConsumerBootstrap.java`
- `rpc-core/src/main/java/com/rpc/core/invoke/proxy/RpcProxyFactory.java`
- `rpc-core/src/main/java/com/rpc/core/invoke/proxy/impl/RpcInvocationHandler.java`

### 7.1 `RpcConsumerBootstrap` 的定位

它是 consumer 启动期总装配器。

它主要负责：

1. 准备 consumer 侧治理环境。
2. 准备服务发现。
3. 准备客户端 transport。
4. 准备代理工厂并创建代理对象。

它的核心成员有：

- `ServiceDiscovery`
- `RpcTransport`
- `RpcProxyFactory`

### 7.2 `fromConfig(...)` 做了什么

它主要做：

1. 创建 consumer 侧降级策略。
2. 配置过滤器和运行时参数。
3. 创建 `ServiceDiscovery`。
4. 组装 `RpcClientConfig`。
5. 创建真正的 `RpcTransport`。
6. 构造 `RpcConsumerBootstrap`。
7. 内部再创建 `RpcProxyFactory`。

所以 `fromConfig(...)` 的本质是：

- 把 consumer 运行所需环境全都准备好。

### 7.3 `getService(Class<T>)` 做了什么

它的目标非常单纯：

- 输入：服务接口类型。
- 输出：这个接口对应的 RPC 代理对象。

返回的不是 provider 真实对象，而是：

- 一个“看起来像本地接口实现，实际上会发远程请求”的代理对象。

### 7.4 `RpcProxyFactory` 的两条路径

它会根据服务类型分成两条代理路线：

1. 接口 -> JDK 动态代理。
2. 普通类 -> CGLIB。

当前项目的主流使用方式是：

- 面向接口编程。

所以大多数情况走的是：

- JDK 动态代理
- `RpcInvocationHandler`

### 7.5 `RpcInvocationHandler` 的定位

它是 consumer 本地方法调用真正进入 RPC 调用链的第一个核心入口。

它的职责可以压缩成一句话：

- 把“本地接口方法调用”翻译成“标准化的 `RpcRequest` 并送入 consumer 调用链”。

---

## 八、Consumer 过滤链：`CONSUMER` 与 `INVOKER`

consumer 侧不是一条过滤链，而是两层：

1. `CONSUMER`
2. `INVOKER`

它们发生在不同位置，职责不同。

### 8.1 `CONSUMER` 过滤链

位置：

- `RpcInvocationHandler`

它更偏：

- 调用入口上下文准备
- 日志上下文
- consumer 侧观测

当前默认顺序是：

1. `trace`
2. `mdc`
3. `consumerMetrics`

### 8.2 `INVOKER` 过滤链

位置：

- `RpcClientInvocationExecutor.execute(...)`

它更偏：

- 真正调用前的治理控制

当前默认主要是：

- `consumerCircuitBreaker`

### 8.3 `TraceFilter`

它的核心逻辑是：

1. 从当前线程 `RpcContext` 里拿 `traceId`。
2. 如果没有，则生成一个。
3. 把这个 `traceId` 写进 `RpcRequest.attachments`。

它的职责非常单纯：

- 保证这次调用链有 `traceId`。
- 并把它透传出去。

它不负责：

- request / response 匹配
- 限流
- 熔断
- 重试

### 8.4 `traceId` 的唯一性和一致性

当前项目里 `traceId` 是通过 UUID 这一类随机标识生成的。

它不是：

- 中心化发号
- 强证明绝对全局唯一

而是：

- 工程上足够唯一

这对链路追踪、日志串联、排障已经够用了。

同一次业务调用如果后面因为 failover / retry 形成多次真实网络请求：

- `traceId` 会保持一致。
- `requestId` 会不同。

原因是：

1. `TraceFilter` 把 `traceId` 写进 `RpcRequest.attachments`。
2. provider 收到请求后，会把它恢复到自己的 `RpcContext`。
3. provider 如果继续向下游发 RPC，在同一线程里 `ensureTraceId()` 会复用已有值。
4. retry 时，当前请求上下文里的 `attachments` 不会被改掉。

所以同一次业务调用的多次真实请求，会共享一个 `traceId`。

### 8.5 `MdcFilter`

`MDC` 是日志框架里的：

- Mapped Diagnostic Context

本质上就是：

- 给当前线程挂一组日志上下文字段。

当前 `MdcFilter` 会把：

- `rpcTraceId`
- `rpcService`
- `rpcMethod`
- 以及当前可用的 `rpcRequestId`

写进 MDC。

需要注意的一个细节是：

- 当前项目里 `requestId` 已经后移到 `invokeOnce(...)` 才生成。
- 所以在 consumer 入口阶段，真实网络请求还没形成。
- 因而 `rpcRequestId` 可能为空。

这不是 bug，而是语义更准确了：

- 链路已经开始，所以有 `traceId`。
- 但真实网络请求还没形成，所以未必有 `requestId`。

### 8.6 `ConsumerMetricsFilter`

它从 consumer 视角统计：

- 整次远程调用耗时。
- 成功 / 失败。

它统计的不是 provider 内部执行情况，而是：

- “我作为调用方调这个服务的体验好不好”。

它包住了后续整个调用链，所以统计到的是：

- consumer 视角的端到端调用耗时。

### 8.7 `ConsumerCircuitBreakerFilter`

它属于 `INVOKER` 层，不属于 `CONSUMER`。

它的核心作用是：

1. 计算 breakerKey。
2. 取服务级或方法级 breaker。
3. 判断当前是否应该熔断 / 降级。
4. 如果该短路，直接返回降级结果。
5. 如果继续执行，则在成功 / 失败后更新 breaker 状态。

这里要特别注意：

- 它操作的是服务级 / 方法级 breaker。
- 不是实例级 breaker。

### 8.8 为什么 breakerKey 有两种粒度

当前支持：

- `serviceName`
- `serviceName#methodName`

虽然 RPC 最终肯定是调某个方法，但治理粒度不一定非要细到方法级。

有时希望：

- 整个服务都一起熔断。

有时希望：

- 只熔断某个特别不稳定的方法。

所以这里分两种 key，不是因为 RPC 不调用方法，而是因为：

- 治理粒度可以是服务级，也可以是方法级。

### 8.9 限流和熔断不是重复

当前 consumer 调用编排里既有限流，又有熔断。

它们不是重复，而是解决不同问题：

#### consumer 限流

关注的是：

- 我自己发得是不是太快了。

目标：

- 控制调用方自己的出站速率。

#### consumer 熔断

关注的是：

- 下游现在是不是已经不健康了。

目标：

- 下游明显不稳定时，不再继续无意义请求。

所以可以理解成：

- 限流管“发多少”。
- 熔断管“还要不要发”。

---

## 九、requestId 与 traceId

这是当前项目里一个非常关键、而且已经被实际修正过的设计点。

### 9.1 两者职责分工

当前最合理的定义是：

#### `traceId`

- 一整条业务调用链的标识。
- 用来串联多跳 RPC、多个服务节点、多个日志点。

#### `requestId`

- 一次真实出网 RPC 请求的标识。
- 用于请求响应匹配、future 回填、网络层精确定位。

所以它们不是重复字段，而是两个不同层级的 ID。

### 9.2 当前项目已经做过的修正

之前项目里存在两个问题：

1. invocation 层太早生成 `requestId`。
2. transport 层又重新生成并覆盖 `requestId`。

这会导致：

- requestId 语义漂移。
- 同一个 requestId 在不同层代表不同值。

当前已经修正成：

1. `RpcInvocationHandler` 不再提前生成 `requestId`。
2. `requestId` 在 `invokeOnce(...)` 中、即将形成一次真实网络请求时生成。
3. transport 层只消费已有 `requestId`，不再自己生成。

### 9.3 为什么 `invokeOnce(...)` 是最合适的生成时机

因为到这一步时：

- consumer 限流已经通过。
- 熔断没有短路。
- cluster 已经决定要执行一次真实调用。
- provider 地址也已经选好了。
- 马上就要真正出网。

这时生成 `requestId` 的语义最准确：

- 一次 `invokeOnce(...)` = 一次真实网络请求 = 一个 `requestId`

### 9.4 requestId 的唯一性要求

当前项目里，`requestId` 不需要做到整个集群全局唯一。

真正需要保证的是：

- 单个 consumer JVM 内。
- 在 pending 请求生命周期里唯一。

因为请求响应匹配发生在：

- 当前 consumer 实例自己的 `RequestManager` 里。

所以只要单 JVM 内唯一就够了。

当前项目已经改成：

- 用 `AtomicLong` 递增生成 `requestId`

这比 `System.nanoTime()` 更可靠，因为：

- `nanoTime()` 不能严格保证并发下不重复。
- `AtomicLong.incrementAndGet()` 可以在单 JVM 内严格递增唯一。

### 9.5 traceId 和多次真实请求的一致性

同一次业务调用，如果因为 failover / retry 触发多次真实网络请求，当前设计就是：

- `traceId` 相同。
- 每次 `requestId` 不同。

这正是当前这套 requestId / traceId 体系要表达的语义。

---

## 十、Consumer 调用编排：`RpcClientInvocationExecutor`

这个类是 consumer 侧真正的调用编排中心。

关键方法是：

- `execute(...)`

### 10.1 `execute(...)` 做了什么

主线是：

1. 解析方法级调用配置 `InvocationOptions`。
2. 执行 consumer 限流。
3. 把高层配置写入 `RpcRequest.attachments`。
4. 执行 `INVOKER` 过滤链。
5. 进入 cluster 层。

所以它的定位不是：

- 直接发包。

而是：

- 决定这次调用应该按什么策略执行。

### 10.2 方法级调用配置解析

它会根据：

- `serviceName`
- `methodName`

解析出本次调用真正生效的运行时参数，比如：

- retryTimes
- clusterStrategy
- readTimeout
- serializerName
- loadBalancerName
- rateLimitEnabled
- rateLimitPermitsPerSecond
- circuitBreakerScope

这些解析结果会汇总成：

- `InvocationOptions`

所以 `InvocationOptions` 可以理解成：

- 一次调用的标准化执行参数。

### 10.3 为什么要把配置写进 `attachments`

例如：

- `readTimeout`
- `serializerName`
- `loadBalancerName`

会被写进 `RpcRequest.attachments`。

原因是：

- 下游 transport / resolver / protocol 层不应该直接依赖高层配置对象。
- 它们更适合只依赖 `RpcRequest`。

所以 attachments 的作用是：

- 把高层调用策略翻译成请求级元数据。

### 10.4 `invokeWithCluster(...)`

它根据 `InvocationOptions` 里的 `clusterStrategy` 选择：

- `FAIL_FAST`
- `FAIL_OVER`

cluster 层回答的是：

- 失败后怎么办。

它并不负责：

- 最终选哪个地址。
- transport 发包。

### 10.5 `invokeOnce(...)`

这是一次真实 RPC 出网请求的最小单元。

它做的事情是：

1. `serviceResolver.resolve(...)` 选地址。
2. 生成这次真实请求的 `requestId`。
3. 调 transport 发请求。
4. 成功时记录实例级 breaker 成功。
5. 失败时抛异常交给 cluster / retry 层处理。

当前对 `requestId` 的最佳理解就是：

- 一个 `invokeOnce(...)` 对应一个 `requestId`

---

## 十一、服务发现、目录缓存与地址选择

这一块关键类有：

- `rpc-core/src/main/java/com/rpc/core/transport/netty/client/invocation/RpcServiceResolver.java`
- `rpc-core/src/main/java/com/rpc/core/discovery/ServiceDirectory.java`
- `rpc-core/src/main/java/com/rpc/core/discovery/ServiceInstancesSnapshot.java`
- `rpc-core/src/main/java/com/rpc/core/discovery/ServiceDiscoveryCache.java`
- `rpc-core/src/main/java/com/rpc/core/registry/zookeeper/ZooKeeperRegistryImpl.java`

### 11.1 `RpcServiceResolver.resolve(...)`

它只是最终选址器。

它不做：

- cluster
- transport
- retry

它只做：

1. 从 `ServiceDirectory` 取当前服务实例快照。
2. 如果没有地址，直接报错。
3. 选具体的负载均衡器。
4. 调 `selectWithCircuitBreaker(...)` 从可用实例里选一个地址。

### 11.2 `ServiceDirectory` 的定位

它不是注册中心本身，而是：

- consumer 本地的服务目录缓存层。

它的目标是：

1. 避免每次调用都直接打 ZooKeeper。
2. 本地缓存服务实例列表。
3. 第一次访问时建立订阅。
4. ZooKeeper 变更时自动更新本地快照。
5. 注册中心临时失败时可回退旧快照。

### 11.3 `ServiceInstancesSnapshot`

它就是：

- 某个服务在某一时刻的实例列表快照。

里面主要是：

- `serviceName`
- `List<InetSocketAddress> addresses`

它不是活对象，只是一张“当前实例列表照片”。

### 11.4 `ServiceDiscoveryCache`

它是：

- `serviceName -> CacheEntry`

而 `CacheEntry` 里既有：

- `snapshot`
- 也有更新时间

所以 `ServiceDirectory` 不只是缓存内容，还能判断：

- 这份快照是否过期。

### 11.5 `ServiceDirectory.getSnapshot(...)` 的主线

1. 先查本地缓存。
2. 如果没过期，直接返回 snapshot。
3. 如果已过期且已经订阅过，则主动 refresh。
4. 如果是第一次访问：
   - 创建 `ServiceChangeListener`
   - 调 `serviceDiscovery.subscribe(serviceName, listener)`
   - 获取初始 snapshot
   - 写入 cache
5. 如果注册中心操作失败，根据配置决定是否回退旧快照。

### 11.6 监听器到底是什么

这里的监听器就是一个单方法接口：

- `ServiceChangeListener`

它本质上是一个回调函数。

`ServiceDirectory` 在第一次访问服务时会传进去一个 listener，大意是：

- “如果这个服务实例列表变了，请把新快照写进我的本地 cache”。

它典型长这样：

```java
nextSnapshot -> cache.put(serviceName, nextSnapshot)
```

所以：

- ZooKeeper 不是直接改 `ServiceDirectory` 的内存。
- 而是 `ZooKeeperRegistryImpl` 收到变化后，再回调这个 listener。
- listener 里执行 `cache.put(...)`。

### 11.7 ZooKeeper watcher 为什么必须重复注册

这是 ZooKeeper 机制本身决定的：

- watcher 是一次性的。
- 触发一次后就失效。
- 后续还想继续监听，必须重新注册。

所以 provider A 下线后，watcher 收到一次 `NodeChildrenChanged` 通知并触发成功之后：

- 这次 watcher 就已经用完了。

如果这时不重新注册，那么后续：

- B 下线
- C 上线

你都收不到通知。

所以 `ZooKeeperRegistryImpl` 在 watcher 回调里再次调用：

- `watchServiceChildren(serviceName)`

不是多余，而是 ZooKeeper 用法上的硬要求。

### 11.8 watcher 如何更新本地缓存

完整链路是：

1. `ServiceDirectory` 第一次访问某个服务。
2. 创建 `ServiceChangeListener`。
3. 调 `ZooKeeperRegistryImpl.subscribe(serviceName, listener)`。
4. `ZooKeeperRegistryImpl` 保存这个 listener。
5. 并挂上 ZooKeeper watcher。
6. 服务节点 children 变化时，watcher 被触发。
7. `ZooKeeperRegistryImpl` 重新读取最新地址列表，生成新的 `ServiceInstancesSnapshot`。
8. 调用之前保存的 listener。
9. listener 执行 `cache.put(serviceName, nextSnapshot)`。
10. `ServiceDirectory` 本地缓存更新完成。

### 11.9 `LoadBalancer.selectWithCircuitBreaker(...)`

地址选择不是对所有地址裸选，而是：

1. 先遍历候选地址。
2. 结合实例级 breaker 过滤掉当前不允许请求的实例。
3. 如果过滤完一个都不剩，抛 `CircuitBreakerException`。
4. 再在健康实例列表里做负载均衡。

所以这里要明确：

- 服务级 / 方法级 breaker 在 `ConsumerCircuitBreakerFilter`。
- 实例级 breaker 在地址选择阶段。

两者不是同一层治理。

### 11.10 当前负载均衡器的几种实现

当前主要有：

- `RandomLoadBalancer`
- `RoundRobinLoadBalancer`
- `LeastConnectionsLoadBalancer`
- `ConsistentHashLoadBalancer`

它们共同点是：

- 都是在“已经通过实例级 breaker 过滤的地址列表”上工作。

需要注意的是：

- 当前一致性哈希实现已经有骨架，但 hash 输入还比较粗，只用 `serviceName`。
- 如果以后真想做稳定业务路由，通常应该用更有业务意义的 key。

---

## 十二、Transport 层：请求发送、连接复用与响应回填

关键类：

- `rpc-core/src/main/java/com/rpc/core/transport/RpcTransport.java`
- `rpc-core/src/main/java/com/rpc/core/transport/netty/client/RpcNettyClient.java`
- `rpc-core/src/main/java/com/rpc/core/transport/netty/client/request/RequestManager.java`
- `rpc-core/src/main/java/com/rpc/core/transport/netty/client/connection/pool/ConnectionPool.java`
- `rpc-core/src/main/java/com/rpc/core/transport/netty/client/handler/RpcClientHandler.java`

### 12.1 当前 transport 对外只保留一条发送路径

当前项目已经做过一次设计收敛：

- 原生 `sendRequestAsync(...)` 已移除。
- transport 对外现在只保留：
  - `sendRequest(...)`

这并不意味着网络底层是同步的。

更准确地说：

- 底层网络 IO 仍然是异步。
- 但 transport 对上层暴露的调用语义统一成同步。

### 12.2 为什么移除原生异步路径

之前的 `AsyncInvocationHandler` 本质上只是：

- 用后台线程把同步主线包成异步 API。

并不是真正独立的原生异步 transport 主线。

而旧的 `sendRequestAsync(...)` 旁路又没有完整复用：

- 方法级配置解析
- 限流
- 熔断
- cluster
- requestId 语义

所以它容易形成不一致。

当前选择直接收敛成：

- 只保留 `sendRequest(...)` 一条主线

是更干净的设计。

### 12.3 `sendRequestToAddress(...)` 做了什么

当前一条真实地址已确定后的发送动作，主线是：

1. 从 `rpcRequest` 里取已有 `requestId`。
2. 在 `RequestManager` 中登记 future。
3. 从 `ConnectionPool` 拿连接。
4. 构造 `RpcMessage`。
5. 编码后写出。
6. 当前线程同步等待 `future.get(...)`。

### 12.4 `RequestManager`

它维护的是：

- `requestId -> CompletableFuture<RpcResponse>`

它的职责是：

1. 请求发出前先登记 future。
2. 响应回来时按 `requestId` 找到 future。
3. 把 future 完成。

这就是当前项目“同步调用外观建立在异步回填之上”的关键。

### 12.5 `ConnectionPool`

它不是复杂连接池，更准确地说是：

- 按 `host:port` 缓存和复用长连接的组件。

它的价值是：

1. 不用每次请求都重新建 TCP 连接。
2. 同一 provider 地址上的请求复用同一条长连接。
3. 减少建连开销。

### 12.6 `RpcClientHandler`

它负责：

1. 收到入站 `RpcMessage`。
2. 判断是心跳响应还是业务响应。
3. 对业务响应调用：
   - `RequestManager.completeResponse(response)`

所以它不是业务恢复器，而是：

- 客户端入站响应分发器。

### 12.7 响应是如何回填的

1. consumer 发请求前，`RequestManager.addRequest(requestId)`。
2. provider 回响应。
3. consumer 解码出 `RpcResponse`。
4. `RpcClientHandler` 把响应交给 `RequestManager`。
5. `RequestManager` 按 `requestId` 找到 future。
6. `future.complete(response)`。
7. 原来阻塞在 `future.get(...)` 的业务线程醒来。

所以 request / response 精确匹配的关键是：

- `requestId`

---

## 十三、协议层：`RpcHeader`、`RpcMessage`、编解码

关键类：

- `rpc-core/src/main/java/com/rpc/core/protocol/message/RpcHeader.java`
- `rpc-core/src/main/java/com/rpc/core/protocol/message/RpcMessage.java`
- `rpc-core/src/main/java/com/rpc/core/protocol/message/RpcMessageType.java`
- `rpc-core/src/main/java/com/rpc/core/protocol/codec/RpcProtocolEncoder.java`
- `rpc-core/src/main/java/com/rpc/core/protocol/codec/RpcProtocolDecoder.java`
- `rpc-core/src/main/java/com/rpc/core/extension/serialize/factory/SerializerFactory.java`

### 13.1 协议层解决的问题

上层在 JVM 里操作的是 Java 对象：

- `RpcRequest`
- `RpcResponse`
- `RpcHeartbeat`

网络上传输的是字节流。

所以协议层负责：

1. 编码：对象 -> 字节。
2. 解码：字节 -> 对象。

### 13.2 `RpcMessage`

它是协议统一载体：

- `header`
- `body`

其中 `body` 可能是：

- `RpcRequest`
- `RpcResponse`
- `RpcHeartbeat`

所以：

- `RpcMessage` 是协议封装对象。
- 不是具体业务模型。

### 13.3 `RpcHeader`

它是固定长度 24 字节，字段包括：

- `magicNumber`
- `version`
- `serializerType`
- `messageType`
- `reserved`
- `requestId`
- `checksum`
- `bodyLength`

字段含义：

#### `magicNumber`

- 标识这是不是本协议的包。

#### `version`

- 协议版本。

#### `serializerType`

- body 用的是什么序列化器。

#### `messageType`

- 这是请求、响应还是心跳。

#### `reserved`

- 预留位。

#### `requestId`

- 这次真实网络请求的标识。

#### `checksum`

- 当前对 body 做 CRC32 校验。

#### `bodyLength`

- body 字节长度。
- 也是解决 TCP 拆包 / 粘包的关键字段。

### 13.4 `RpcProtocolEncoder`

编码主线是：

1. 取 `RpcHeader`。
2. 取 `body`。
3. 根据 `serializerType` 找 `Serializer`。
4. 把 body 序列化成 `bodyBytes`。
5. 计算 `bodyLength`。
6. 计算 `checksum`。
7. 按固定顺序写 header。
8. 再写 body。

这里很关键的一点是：

- header 写入顺序必须和 decoder 读取顺序完全一致。

### 13.5 `RpcProtocolDecoder`

它继承 `LengthFieldBasedFrameDecoder`。

这是当前项目解决 TCP 拆包 / 粘包的关键。

原因是：

- TCP 只有字节流，没有消息边界。
- `bodyLength` 让 Netty 知道一条完整消息的边界在哪里。

解码主线是：

1. 先按 `bodyLength` 切出完整 frame。
2. 读 header。
3. 校验 `magicNumber`。
4. 校验 `version`。
5. 读取 bodyBytes。
6. 校验 `checksum`。
7. 根据 `serializerType` 找 `Serializer`。
8. 根据 `messageType` 决定把 body 解成：
   - `RpcRequest`
   - `RpcResponse`
   - `RpcHeartbeat`
9. 最后还原成 `RpcMessage`。

### 13.6 `SerializerFactory`

协议头里只有一个 `serializerType` 数字，但真正序列化 / 反序列化需要拿到具体实现。

所以：

- `SerializerFactory` 负责把类型码映射到具体 `Serializer`。

协议层不需要直接知道：

- JDK
- JSON
- Kryo

这些实现细节，只需要：

- 读头里的类型码。
- 去工厂拿序列化器。

---

## 十四、Provider 回包与 consumer 完成闭环

provider 业务执行完后：

1. 得到 `RpcResponse`。
2. `RpcRequestDispatcher` 基于原请求头构造响应 `RpcMessage`。
3. 沿用：
   - `requestId`
   - `serializerType`
4. `RpcProtocolEncoder` 编码回 consumer。

consumer 收到后：

1. `RpcProtocolDecoder` 解码。
2. `RpcClientHandler` 拿到 `RpcResponse`。
3. `RequestManager.completeResponse(response)`。
4. 通过同一个 `requestId` 完成 future。
5. 最初阻塞线程醒来。
6. `RpcInvocationHandler` 把 `response.getData()` 还原为业务代码看到的返回值。

这一点非常关键：

- `requestId` 必须贯穿“一次真实请求 -> 一次真实响应”。
- 否则 consumer 无法回填到正确 future。

---

## 十五、整条链的完整闭环总串

这里把整个项目从启动到一次请求执行完整串起来。

### 15.1 启动期

#### provider

1. Spring 或 starter 注册 `@RpcService` BeanDefinition。
2. Spring 实例化真实服务 Bean。
3. `RpcSpringManager.start()` 找到所有 `@RpcService` Bean。
4. `RpcProviderBootstrap.registerService(...)`。
5. `LocalRegistryImpl.register(...)`
   - 本地注册：`serviceName -> serviceInstance`
   - 注册中心：`serviceName -> host:port`
6. `RpcProviderBootstrap.start()`。
7. `RpcNettyServer` 绑定端口开始监听。

#### consumer

1. Spring 创建普通业务 Bean。
2. `RpcSpringManager.postProcessBeforeInitialization(...)`。
3. 扫描 `@RpcReference`。
4. `RpcConsumerBootstrap.getService(...)`。
5. `RpcProxyFactory` 创建代理。
6. 代理注入到业务 Bean 字段中。

### 15.2 一次 consumer 调用

1. 业务代码调用代理对象方法。
2. `RpcInvocationHandler` 构造 `RpcRequest`。
3. `CONSUMER` 过滤链：
   - `TraceFilter`
   - `MdcFilter`
   - `ConsumerMetricsFilter`
4. `client.sendRequest(...)`
5. `RpcClientInvocationExecutor.execute(...)`
   - 解析方法级配置
   - consumer 限流
   - `INVOKER` 熔断治理
6. `invokeWithCluster(...)`
7. 每次 `invokeOnce(...)`
   - `serviceResolver.resolve(...)`
   - 从 `ServiceDirectory` 取实例快照
   - 结合实例 breaker 和负载均衡选地址
   - 生成这次真实请求的 `requestId`
   - transport 发请求

### 15.3 transport 和协议层

1. `RequestManager` 登记 `requestId -> future`。
2. `ConnectionPool` 拿连接。
3. 构造 `RpcMessage`。
4. `RpcProtocolEncoder` 编码成字节流。
5. Netty 发给 provider。

### 15.4 provider 收包执行

1. `RpcProtocolDecoder` 解码成 `RpcMessage`。
2. `RpcRequestHandler`。
3. `RpcRequestDispatcher`
   - 心跳请求 -> 直接回心跳
   - 业务请求 -> 交给 `RpcRequestExecutor`
4. `RpcRequestExecutor`
   - inflight +1
   - 扔进 biz 线程池
5. 在线程池里：
   - 恢复 `RpcContext`
   - 查本地注册表
   - 执行 `PROVIDER` 过滤链
   - 反射调用真实方法
   - 包装 `RpcResponse`

### 15.5 provider 回包 + consumer 回填

1. provider 构造响应 `RpcMessage`。
2. 沿用 `requestId` 和 `serializerType`。
3. 编码回 consumer。
4. consumer 解码响应。
5. `RpcClientHandler` -> `RequestManager.completeResponse(...)`。
6. 通过 `requestId` 找到 future 并完成。
7. 最初调用线程醒来。
8. 代理把 `RpcResponse.data` 还原给业务代码。

### 15.6 整条链的 ID 语义

- `traceId`
  - 整条调用链共享。
- `requestId`
  - 每次真实网络请求独立生成。

所以：

- 一次业务调用多次重试 -> 同一个 `traceId`，多个不同 `requestId`

---

## 十六、当前项目中已经识别和修正过的设计点

### 16.1 requestId 语义修正

之前存在的问题：

1. invocation 层提前生成 `requestId`。
2. transport 层又重新生成并覆盖。

现在已经修正成：

1. `requestId` 只在 `invokeOnce(...)` 生成。
2. transport 层只消费，不再重建。
3. requestId 只需要保证单 JVM 内唯一。
4. 当前使用 `AtomicLong` 保证单 JVM 递增唯一。

### 16.2 CGLIB 路径和 Socket 路径已对齐

之前 `RpcMethodInterceptor` 和 `RpcSocketClient` 在 requestId 语义上与主线不一致，现在已经统一到：

- requestId 在真正出网前生成。

### 16.3 原生异步发送旁路已移除

之前存在：

- `AsyncInvocationHandler`
- `sendRequestAsync(...)`

但这条路本质上没有形成一条完整、闭环、语义一致的原生异步 RPC 主线。

最终收敛成：

- transport 对外只保留 `sendRequest(...)`

所以当前项目设计更清晰：

- 底层网络仍然异步。
- 对外 transport API 统一成同步语义。

### 16.4 当前仍值得继续关注的小点

1. 实例级 breaker 在失败路径上的记录逻辑，后续值得再单独审一下是否完整。
2. consumer 在 `invokeOnce(...)` 生成 requestId 之后，如果想更细粒度观测 retry / failover，每次真实请求开始时把 requestId 再写入更靠后的 MDC，会更有价值。
3. 一致性哈希当前只用 `serviceName` 做 hash，后续如果真要用于稳定路由，通常应换成更有业务意义的 key。

---

## 十七、后续最值得继续深挖的专项

当前主线已经基本打通，后面更适合进入专项深挖。

我目前认为最值得按顺序继续看的模块是：

1. 熔断器状态机
   - `CircuitBreakerImpl`
   - `CLOSED / OPEN / HALF_OPEN`
   - 服务级 breaker 和实例级 breaker 协同
2. 序列化扩展体系
   - `Serializer`
   - `SerializerFactory`
   - 不同序列化器如何接入协议层
3. 负载均衡实现差异
   - random
   - roundRobin
   - leastConnections
   - consistentHash
4. provider 优雅停机全过程
   - `ServerLifecycle`
   - inflight
   - 停止接收新请求
   - 从注册中心摘除
   - biz 线程池与网络资源关闭

如果按“理解收益最高”排序，我下一步最建议继续看：

- 熔断器状态机

因为：

1. 你已经理解了 consumer 侧限流、熔断、cluster、实例 breaker 的大框架。
2. 再把 breaker 状态机看透，这块治理层就会真正完整。

---

## 十八、Breaker 状态机专项深挖

这一部分专门记录 consumer 侧 breaker 的状态机实现、分层方式、已识别问题以及已经完成的收敛修改。

关键类：

- `rpc-core/src/main/java/com/rpc/core/resilience/CircuitBreaker.java`
- `rpc-core/src/main/java/com/rpc/core/resilience/CircuitBreakerState.java`
- `rpc-core/src/main/java/com/rpc/core/resilience/circuitbreaker/CircuitBreakerImpl.java`
- `rpc-core/src/main/java/com/rpc/core/resilience/circuitbreaker/CircuitBreakerManager.java`
- `rpc-core/src/main/java/com/rpc/core/invoke/filter/impl/ConsumerCircuitBreakerFilter.java`

### 18.1 当前项目里 breaker 分两层

这个点非常重要。

当前项目里不是“一个熔断器管所有事”，而是两类 breaker：

1. 服务级 / 方法级 breaker
2. 实例级 breaker

在 `CircuitBreakerManager` 里分别维护：

- `serviceCircuitBreakers`
- `instanceCircuitBreakers`

它们解决的问题不同。

#### 服务级 / 方法级 breaker

关注的是：

- 这个服务整体是不是不稳定
- 或这个方法整体是不是不稳定

它的 key 可能是：

- `serviceName`
- `serviceName#methodName`

它主要在：

- `ConsumerCircuitBreakerFilter`

里使用。

#### 实例级 breaker

关注的是：

- 某一台具体 provider 节点是不是不健康

它的 key 是：

- `serviceName#host:port`

它主要在：

- 地址选择阶段
- `LoadBalancer.selectWithCircuitBreaker(...)`
- 以及 `invokeOnce(...)`

里参与。

所以这两层 breaker 的职责分别是：

- 服务级 / 方法级 breaker：判断“这个服务整体还值不值得继续打”
- 实例级 breaker：判断“这台具体节点还值不值得继续选”

### 18.2 breaker 接口的职责

`CircuitBreaker` 接口只有五个方法：

- `allowRequest()`
- `recordSuccess()`
- `recordFailure()`
- `getState()`
- `reset()`

它们分别表示：

#### `allowRequest()`

- 当前这次请求还能不能继续通过

#### `recordSuccess()`

- 刚刚有一次调用成功了

#### `recordFailure()`

- 刚刚有一次调用失败了

#### `getState()`

- 当前 breaker 的状态是什么

#### `reset()`

- 手动重置 breaker

所以 breaker 不是简单配置对象，而是：

- 一个会随着调用结果变化的运行时状态机对象

### 18.3 breaker 的三种状态

`CircuitBreakerState` 里定义了三种状态：

- `CLOSED`
- `OPEN`
- `HALF_OPEN`

当前理解：

#### `CLOSED`

- 正常放流量
- 同时持续统计成功 / 失败

#### `OPEN`

- 直接拒绝请求
- 暂停继续打下游
- 等待恢复窗口到期

#### `HALF_OPEN`

- 放少量探测流量
- 判断下游是否恢复
- 探测成功则恢复
- 探测失败则重新熔断

### 18.4 `CircuitBreakerImpl` 内部维护了什么

它内部主要分成两类字段：

#### 配置参数

- `failureRateThreshold`
- `minNumberOfCalls`
- `waitDurationInOpenState`
- `permittedNumberOfCallsInHalfOpenState`

分别表示：

- 失败率阈值
- 最小调用次数
- OPEN 持续时长
- HALF_OPEN 允许放过的探测请求数

#### 运行时状态

- `totalCalls`
- `failedCalls`
- `halfOpenCalls`
- `halfOpenSuccessCalls`
- `state`
- `lastFailureTime`

其中：

- `totalCalls / failedCalls`
  - 用于统计当前一轮中的总调用数和失败数
- `halfOpenCalls`
  - HALF_OPEN 已经放过多少个探测请求
- `halfOpenSuccessCalls`
  - HALF_OPEN 阶段已经成功了多少个探测请求
- `state`
  - 当前 breaker 状态
- `lastFailureTime`
  - 最近一次失败时间，用来判断 OPEN 已经持续了多久

### 18.5 当前 breaker 状态机的完整流转

#### `CLOSED -> OPEN`

触发条件：

- 总调用数 `>= minNumberOfCalls`
- 失败率 `>= failureRateThreshold`

#### `OPEN -> HALF_OPEN`

触发条件：

- `OPEN` 已持续至少 `waitDurationInOpenState`

#### `HALF_OPEN -> OPEN`

触发条件：

- 探测请求失败

#### `HALF_OPEN -> CLOSED`

触发条件：

- 探测窗口内允许通过的请求都成功

### 18.6 当前 breaker 统计模型的特点

当前 `CircuitBreakerImpl` 在 `CLOSED` 状态下用的是：

- 累计总调用数
- 累计失败数

来计算失败率。

它不是：

- 滑动窗口
- 固定时间桶
- 最近 N 秒失败率

而是：

- 自上次 `resetStatistics()` 以来的累计统计

所以它的特点是：

1. 实现简单
2. 好理解
3. 更像看“本轮整体表现”
4. 对最近突然恶化的趋势不够敏感
5. 早期失败会被后续成功逐渐稀释

这不是 bug，但属于当前 breaker 统计模型仍然偏基础版的地方。

### 18.7 当前项目里 breaker 原始实现存在的两个主要问题

在这次专项审查前，breaker 主线里有两个比较明显的问题。

#### 问题 1：`OPEN -> HALF_OPEN` 的状态推进逻辑写了两份

原始实现里：

- `allowRequest()` 会在 OPEN 超时后推进到 `HALF_OPEN`
- `getState()` 也会在 OPEN 超时后推进到 `HALF_OPEN`

这会导致：

1. 状态推进职责分散
2. `getState()` 不再只是纯读取
3. 后续维护时更容易出现逻辑不一致

#### 问题 2：`HALF_OPEN -> CLOSED` 恢复策略过于乐观

原始实现里，只要 HALF_OPEN 阶段有一次探测成功，就会立即：

- `HALF_OPEN -> CLOSED`

这意味着：

- 一次成功就完全恢复全部流量

这个策略简单，但过于乐观。

### 18.8 已经完成的收敛修改：状态推进入口收敛

当前已经做过一次收敛修改：

- 只保留 `allowRequest()` 推进 `OPEN -> HALF_OPEN`
- `getState()` 退回成纯读取

所以现在 breaker 的语义更清晰：

#### `allowRequest()`

- 是准入判断入口
- 必要时推进状态

#### `getState()`

- 只是读取当前状态
- 不再顺手修改状态

这样做的好处是：

1. 状态推进职责集中
2. 读状态和改状态不再混在一起
3. 后续如果继续改 HALF_OPEN 策略，只需要盯一个入口

### 18.9 已经完成的收敛修改：HALF_OPEN 恢复策略更稳

当前也已经做过第二次收敛：

- 不再“一次探测成功就完全恢复”
- 改成“探测窗口成功后再恢复”

现在的语义是：

1. HALF_OPEN 最多放 `permittedNumberOfCallsInHalfOpenState` 个探测请求
2. 只有当这批探测请求都成功，才会：
   - `HALF_OPEN -> CLOSED`
3. 任何一次探测失败，仍然会：
   - `HALF_OPEN -> OPEN`

这比原来的实现更稳，也更接近真实治理语义。

### 18.10 服务级 / 方法级 breaker 在调用链里怎么工作

服务级 / 方法级 breaker 的主入口在：

- `ConsumerCircuitBreakerFilter`

它的主线是：

1. 根据请求和配置计算 `breakerKey`
2. 从 `CircuitBreakerManager` 取服务级 breaker
3. 调 `allowRequest()`
4. 如果不允许请求，就直接降级
5. 如果继续执行：
   - 成功时 `recordSuccess()`
   - 失败时 `recordFailure()`

这里的关键结论是：

- 服务级 / 方法级 breaker 主要负责“这个服务整体还值不值得继续打”

### 18.11 实例级 breaker 在调用链里怎么工作

实例级 breaker 主要参与两处：

#### 地址选择阶段

在 `LoadBalancer.selectWithCircuitBreaker(...)` 中：

1. 遍历候选地址
2. 对每个地址取实例 breaker
3. 调 `allowRequest()`
4. 不健康的实例直接过滤掉
5. 再在健康实例里做负载均衡

#### 一次真实请求执行后

在 `RpcClientInvocationExecutor.invokeOnce(...)` 中：

- 成功时 `recordSuccess()`
- 失败时 `recordFailure()`

这意味着：

- 实例级 breaker 终于真正形成了完整闭环

### 18.12 已经识别并修复的问题：实例级 breaker 失败路径原本缺失

这一点是这次代码审计里非常重要的发现。

原始实现里：

- 实例级 breaker 在地址选择阶段会被读取
- 在 `invokeOnce(...)` 成功时会 `recordSuccess()`
- 但没有明确的失败路径对它调用 `recordFailure()`

这会导致：

- 实例级 breaker 很难真正从失败样本里进入 `OPEN`
- 地址选择阶段虽然支持“过滤不健康实例”，但坏节点几乎不会被真正熔断出来

当前这个问题已经修复：

- 在 `invokeOnce(...)` 中，针对已经选中的具体 `address`
- 只要这次真实请求失败或返回非 `200`
- 就会对该实例 breaker 调 `recordFailure()`

修完后的闭环是：

1. 选中某个实例
2. 真实请求它
3. 成功 / 失败反馈给该实例 breaker
4. 下次地址选择时，breaker 状态再决定这台机器还能不能继续进入候选集

### 18.13 原始实现里还有一层重复治理：failure counter + breaker 双轨并行

在这次收敛之前，consumer 侧还存在另一套机制：

- `FilterRuntimeConfig` 里的失败计数器

它原本和 breaker 状态机叠加在一起，逻辑是：

1. breaker 自己决定是否允许请求
2. 另外再用一个 `ConcurrentHashMap<String, AtomicInteger>` 统计失败次数
3. 失败次数达到阈值，也会直接降级

这样会形成两套并行规则：

1. breaker 状态机
2. 简单失败计数短路

这会带来一个问题：

- 当前到底是谁在决定“不再继续请求”这件事，语义会变得不够干净

### 18.14 已经完成的收敛修改：移除 failure counter 旁路

当前已经把这套额外失败计数器移除掉了。

收敛后的语义变成：

- consumer 降级只由 breaker 状态机决定

也就是说：

1. `ConsumerCircuitBreakerFilter` 只看 breaker 的 `allowRequest()`
2. 成功时只 `recordSuccess()`
3. 失败时只 `recordFailure()`
4. 不再额外 reset / increment 另一套失败计数器

同时，对应的外围配置也已经一并收口：

- `degradationFailureThreshold`
- `CLIENT_DEGRADATION_FAILURE_THRESHOLD`
- 相关 runtime config、bootstrap config、properties、测试残留

这样 consumer 侧 breaker 语义就干净很多了：

- breaker 负责熔断与恢复
- degradation policy 负责“已经决定降级时怎么返回”
- 不再有第三套失败次数短路机制混在中间

### 18.15 当前 breaker 这条线收敛后的状态

到目前为止，这条线已经完成了 4 个关键收敛：

1. 实例级 breaker 失败路径补齐
2. `OPEN -> HALF_OPEN` 状态推进只保留在 `allowRequest()`
3. `HALF_OPEN -> CLOSED` 改成探测窗口成功后再恢复
4. consumer 侧额外 failure counter 旁路移除，降级只由 breaker 状态机决定

所以当前 breaker 体系已经比最初实现干净很多了。

### 18.16 breaker 这条线目前还剩的主要改进点

当前这条线剩下最明显的改进点主要有两个：

#### 改进点 1：统计模型仍然是累计统计，不是滑动窗口

当前失败率判断用的是：

- 自上次 reset 以来的累计总调用数
- 自上次 reset 以来的累计失败数

所以它对“最近突然恶化”不够敏感。

#### 改进点 2：HALF_OPEN 仍然是“全部探测成功再恢复”的简化策略

现在已经比“一次成功立刻恢复”更好，但仍然是较简单的实现。

未来如果真想更贴近生产治理，可以继续往：

- 滑动窗口
- 更细粒度探测成功率判断

这些方向演进。


---

## 十九、序列化层：Java 对象如何变成协议 body

这一节只解决一个问题：consumer 和 provider 之间传输的不是 Java 对象本身，而是字节。`RpcRequest`、`RpcResponse`、`RpcHeartbeat` 这些对象要经过序列化器变成 body 字节，再由协议层加上 header 后写入网络。

### 19.1 `Serializer` 的职责

源码位置：

- [Serializer.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/serialize/Serializer.java)

核心方法：

- `serialize(T obj)`：把对象变成字节数组。
- `deserialize(byte[] bytes, Class<T> clazz)`：把字节数组还原成目标类型对象。
- `getSerializerType()`：返回当前序列化器对应的协议 type code。

这里要注意：`Serializer` 不关心网络连接，也不关心 Netty buffer。它只负责对象和字节之间的转换。

### 19.2 为什么 `Serializer` 是 SPI 扩展点

`Serializer` 是一个可替换能力。当前项目支持多种实现：

- `ProtobufSerializer`
- `JsonSerializer`
- `KryoSerializer`
- `HessianSerializer`
- `JavaSerializer`

所以它被设计成 SPI 扩展点。默认序列化器可以通过 `@SPI("protobuf")` 这类方式指定，也可以通过配置选择具体实现。

第一遍只要记住：

`SPI 解决的是按名字找到实现类的问题，Serializer 解决的是对象和字节转换的问题。`

### 19.3 `SerializerType` 的作用

源码位置：

- [SerializerType.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/serialize/SerializerType.java)

配置和 SPI 里通常用名字，例如 `json`、`protobuf`、`kryo`。但协议头里不能只写一个 Java 类名或字符串，它需要一个稳定的 type code。

`SerializerType` 就是这个映射层：

```text
serializer name -> serializer type code -> protocol header
```

这样 decoder 收到消息后，才能通过 header 里的 `serializerType` 选择同一种反序列化器。

### 19.4 `SerializerFactory` 的职责

源码位置：

- [SerializerFactory.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/serialize/factory/SerializerFactory.java)

它负责把两类信息接起来：

1. SPI 扩展名，例如 `protobuf`、`json`、`kryo`。
2. 协议 type code，例如 header 里的 `serializerType`。

所以它既能按名字拿序列化器，也能按 type code 反查序列化器。

这对 decoder 很关键。因为 decoder 读到的是协议头里的数字，而不是业务配置里的字符串。

### 19.5 encoder 怎么使用序列化器

源码位置：

- [RpcProtocolEncoder.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/protocol/codec/RpcProtocolEncoder.java)

编码主线：

1. 准备 `RpcHeader`。
2. 从 header 或上下文确定 `serializerType`。
3. 通过 `SerializerFactory` 找到对应 `Serializer`。
4. 把 body 对象序列化为 `bodyBytes`。
5. 写入 `bodyLength`。
6. 计算并写入 checksum。
7. 先写 header，再写 body。

核心判断：

`serializerType` 决定 body 应该用 JSON、Kryo、Protobuf 还是其他方式编码。

### 19.6 decoder 怎么使用序列化器

源码位置：

- [RpcProtocolDecoder.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/protocol/codec/RpcProtocolDecoder.java)

解码主线：

1. 读取 header。
2. 校验 magic、version、checksum 等基础字段。
3. 根据 `bodyLength` 读取 body 字节。
4. 根据 header 里的 `serializerType` 找到 `Serializer`。
5. 根据 header 里的 `messageType` 判断 body 应该还原为 `RpcRequest`、`RpcResponse` 还是 `RpcHeartbeat`。
6. 反序列化并组装 `RpcMessage`。

这里有两个关键信息：

- `serializerType` 决定“怎么反序列化”。
- `messageType` 决定“反序列化成什么对象”。

### 19.7 本节结论

序列化层不是单独存在的工具层。它通过 `SerializerFactory` 和协议头字段接入协议编解码流程：consumer 发请求时写入 type code，provider 收请求时按同一个 type code 反序列化；provider 回包时同理。

---

## 二十、SPI 机制：扩展名如何落到实现类

这一节只解决一个问题：当前项目不是到处 `new JsonSerializer()` 或 `new RoundRobinLoadBalancer()`，而是通过 SPI 按名字加载扩展实现。

### 20.1 `@SPI` 的含义

源码位置：

- [SPI.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/spi/SPI.java)

`@SPI` 标在扩展接口上，表示这个接口可以通过扩展机制加载实现。

例如 `Serializer` 标注默认值后，含义是：

1. `Serializer` 是 SPI 扩展接口。
2. 如果调用方没有指定名字，可以使用默认实现名。

### 20.2 `ExtensionLoader` 的职责

源码位置：

- [ExtensionLoader.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/spi/ExtensionLoader.java)

`ExtensionLoader` 是 SPI 的核心加载器。它主要维护两类缓存：

1. 扩展接口类型到 loader 的缓存。
2. 扩展名到实现类、扩展名到单例实例的缓存。

第一遍可以把它理解成：

`给我一个扩展接口和扩展名，它负责找到实现类并创建实例。`

### 20.3 扩展配置文件

扩展配置位于：

```text
rpc-core/src/main/resources/META-INF/rpc/
```

文件名通常是扩展接口全限定名，文件内容是：

```text
name=implementationClass
```

例如序列化器可以通过类似下面的映射声明：

```text
protobuf=com.rpc.core.extension.serialize.impl.ProtobufSerializer
json=com.rpc.core.extension.serialize.impl.JsonSerializer
```

这样框架就能通过 `protobuf` 或 `json` 这个名字找到对应实现类。

### 20.4 `getExtension(name)` 做了什么

典型流程：

1. 检查扩展接口是否标注 `@SPI`。
2. 从 `META-INF/rpc/` 加载扩展名和实现类映射。
3. 根据 name 找到实现类。
4. 创建实例。
5. 执行依赖注入。
6. 执行初始化方法。
7. 缓存单例实例。

所以 SPI 不是只做 `Class.forName`，它还包含实例缓存、依赖注入和初始化钩子。

### 20.5 `@Inject` 的作用

源码位置：

- [Inject.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/spi/Inject.java)

`@Inject` 用于给扩展实现注入依赖。它可以指定扩展名，也可以标记依赖是否必需。

第一遍只要知道：如果一个 SPI 实现依赖另一个 SPI 扩展，`ExtensionLoader` 会在创建实例时尝试注入。

### 20.6 `@Initialize` 的作用

源码位置：

- [Initialize.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/spi/Initialize.java)

`@Initialize` 标记初始化方法。扩展实例创建和注入完成后，加载器会调用这些初始化方法。

典型顺序是：

```text
new 实例 -> 注入依赖 -> 调用初始化方法 -> 缓存实例
```

### 20.7 `ExtensionFactory` 的职责

源码位置：

- [ExtensionFactory.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/spi/ExtensionFactory.java)

`ExtensionFactory` 是对 `ExtensionLoader` 的门面封装，提供更直接的 API：

- `getDefaultExtension(type)`
- `getExtension(type, name)`
- `getExtensions(type)`
- `getSupportedExtensions(type)`
- `hasExtension(type, name)`

业务侧和工厂类不需要直接关心 loader 的细节时，就可以通过它取扩展。

### 20.8 本节结论

当前项目的 SPI 机制服务于“可替换能力”：序列化器、负载均衡器这类组件都可以按名字选择实现。它不是 Spring IOC 的替代品，而是框架内部扩展点的轻量加载机制。

---
## 二十一、SPI 的真实落地链：序列化器如何从名字落到协议头 type code

前面已经把序列化层和 SPI 机制分别讲清楚了，但这两块真正有价值的地方，在于它们在当前项目里是如何接起来的。序列化器不是“SPI 自己玩一套，协议层自己玩一套”，而是一路从：

- 配置里的序列化器名字
- 到 SPI 扩展实例
- 再到协议头里的 `serializerType`
- 最后到 encoder / decoder 真正编解码

形成一条完整链。

### 21.1 先把这条链的关键角色列出来

这一条链最关键的类有：

- [Serializer.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/serialize/Serializer.java)
- [SerializerType.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/serialize/SerializerType.java)
- [SerializerFactory.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/serialize/factory/SerializerFactory.java)
- [RpcClientInvocationExecutor.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/netty/client/invocation/RpcClientInvocationExecutor.java)
- [RpcNettyClient.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/netty/client/RpcNettyClient.java)
- [RpcProtocolEncoder.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/protocol/codec/RpcProtocolEncoder.java)
- [RpcProtocolDecoder.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/protocol/codec/RpcProtocolDecoder.java)

你可以先把它们分成三层：

1. SPI 层
   - `Serializer`
   - `ExtensionLoader`
   - `ExtensionFactory`
2. 类型码桥接层
   - `SerializerType`
   - `SerializerFactory`
3. 协议编解码层
   - `RpcProtocolEncoder`
   - `RpcProtocolDecoder`

### 21.2 序列化器为什么不像负载均衡器那样只按名字工作

负载均衡器在运行时主要按名字取实现：

- `random`
- `roundrobin`

最后直接拿到一个 `LoadBalancer` 实例去用就行了。

但序列化器不一样。因为序列化器不仅发生在“本地调用编排”阶段，还发生在：

- consumer 发请求编码时
- provider 收请求解码时
- provider 回响应编码时
- consumer 收响应解码时

这就意味着：

- 发送方和接收方必须就“本次 body 用哪种序列化器”达成一致

这个一致性不能只靠本地名字，因为网络上传过去的是字节，不是 Java 配置对象。所以序列化器必须最终落到协议头里，变成一个跨网络可识别的标识。当前项目选择的方式就是：

- 协议头里放一个 `serializerType` 数字

因此序列化器这条线会比负载均衡器多出一层：

- 名字 -> type code -> 实现

### 21.3 `Serializer` SPI 先解决“有哪些实现”

先看 [Serializer.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/serialize/Serializer.java)。

它是一个带 `@SPI("protobuf")` 的接口，这说明：

1. `Serializer` 是一个 SPI 扩展点
2. 默认扩展名是 `protobuf`

SPI 配置文件在：

- [Serializer](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/resources/META-INF/rpc/com.rpc.core.extension.serialize.Serializer)

里面注册了：

- `protobuf`
- `kryo`
- `json`
- `hessian`
- `java`

所以通过 SPI 机制，框架已经解决了第一个问题：

- 当前有哪些序列化实现
- 默认实现叫什么
- 按名字怎么拿到某个实现对象

### 21.4 `SerializerType` 再解决“协议里怎么表示它”

光有扩展名还不够，因为线上消息头不适合直接塞：

- `"protobuf"`
- `"json"`

这样的字符串。

所以当前项目引入了 [SerializerType.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/serialize/SerializerType.java) 注解。

每个实现类会通过它声明一个数字类型码。也就是说，当前每个序列化器有两套身份：

1. SPI 身份
   - 扩展名，例如 `protobuf`
2. 协议身份
   - 类型码，例如 `5`

这是序列化器这条线和其他 SPI 扩展点最关键的区别。

### 21.5 `SerializerFactory` 是这两套身份之间的桥

[SerializerFactory.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/extension/serialize/factory/SerializerFactory.java) 的意义就在这里。

它不是简单地“按名字拿扩展”，而是要解决：

- 已知协议头里的 type code，如何找到真正的 `Serializer`

它大致做的事情是：

1. 通过 SPI 发现所有 `Serializer` 实现
2. 读取实现类上的 `@SerializerType`
3. 建立：
   - `type -> extensionName`
   - `type -> Serializer实例`
   的映射
4. decoder / encoder 只要拿到 `serializerType`，就能反查出真正实现

所以你可以把 `SerializerFactory` 理解成：

- 协议类型码和 SPI 扩展机制之间的桥接层

### 21.6 这条链的起点：方法级配置或默认配置先决定“本次想用哪个序列化器”

在 consumer 侧调用编排里，`InvocationOptionsResolver` 会先解析：

- 全局默认配置
- 方法级覆盖配置

最终得到本次调用的 `InvocationOptions`。

其中就可能包括：

- 本次调用应该使用哪个 serializer name

然后在 [RpcClientInvocationExecutor.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/netty/client/invocation/RpcClientInvocationExecutor.java) 的：

- `applyInvocationOptions(rpcRequest, options)`

里，把这个名字写进：

- `RpcRequest.attachments`

这一步的意义是：

- 高层配置先被折叠成请求元数据

所以当前调用编排阶段，还只是：

- 知道“本次想用哪个序列化器名字”

还没有真正写协议头。

### 21.7 真正落到协议头是在 transport / protocol 层

到了 [RpcNettyClient.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/netty/client/RpcNettyClient.java) 里构造请求消息时，会根据：

- `RpcRequest.attachments`

里的 serializer 配置，解析出本次真正要写入 header 的：

- `serializerType`

然后构造 `RpcHeader`。

也就是说，这一层完成的是：

- 序列化器扩展名 / 配置表达
- 收敛成协议头里的数字类型码

所以真正上线的是：

- `RpcHeader.serializerType`

而不是：

- `"json"`
- `"protobuf"`

这种名字。

### 21.8 consumer 发请求时，这个 type code 怎么被使用

到了 [RpcProtocolEncoder.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/protocol/codec/RpcProtocolEncoder.java) 里，编码流程是：

1. 拿 `RpcHeader`
2. 读 `header.getSerializerType()`
3. 调：

```java
SerializerFactory.getSerializer(header.getSerializerType())
```

4. 拿到真正的 `Serializer`
5. 对 body 执行 `serialize(...)`
6. 得到 `bodyBytes`

所以当前项目里，encoder 并不会问：

- “这是不是 protobuf 名字”

它只认：

- 协议头里的 type code

然后通过 `SerializerFactory` 找到实现。

这一步说明一件事：

- 编码阶段真正生效的序列化器选择，不在业务对象里，也不在 SPI 文件里，而在 `RpcHeader.serializerType`

### 21.9 provider 收请求时，这个 type code 怎么被用回来

provider 端收到消息后，在 [RpcProtocolDecoder.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/protocol/codec/RpcProtocolDecoder.java) 里会：

1. 先读 header
2. 取出 `serializerType`
3. 调：

```java
SerializerFactory.getSerializer(header.getSerializerType())
```

4. 再结合 `messageType`
5. 把 body 反序列化成：
   - `RpcRequest`
   - `RpcResponse`
   - `RpcHeartbeat`

这里非常关键的一点是：

- provider 并不需要知道 consumer 本地配置里写的是什么名字
- 它只要读到 header 里的 type code，就能通过同一套 `SerializerFactory` 找到正确实现

这就是跨网络达成一致的关键。

### 21.10 provider 回响应时，为什么还能继续用同一种 serializer

provider 在回响应时，通常会沿用请求头里的：

- `serializerType`

所以它在编码响应时，又会走一遍：

```java
SerializerFactory.getSerializer(header.getSerializerType())
```

这就保证了：

- consumer 请求怎么编码过去
- provider 就按同一套 serializer 回来

否则 consumer 端响应解码时会出现：

- 发请求用了一种 serializer
- 回响应却用了另一种

那就根本解不出来。

所以当前项目里，`serializerType` 不只是请求用一次，而是：

- 请求和响应整条通信链都要保持一致

### 21.11 consumer 收响应时，链路如何闭环

响应回到 consumer 后，decoder 再次做：

1. 读 header 的 `serializerType`
2. `SerializerFactory.getSerializer(...)`
3. 把 body 解成 `RpcResponse`

所以整条链现在就完整闭环了：

1. 高层配置决定 serializer name
2. transport/protocol 层把 name 收敛成 header type code
3. encoder 按 type code 找 serializer 实现并编码
4. 对端 decoder 按同一个 type code 找 serializer 实现并解码
5. 响应沿用同一个 serializerType 再回传
6. consumer 再按这个 type code 解回 `RpcResponse`

### 21.12 这条链和普通 SPI 扩展点最大的区别

像负载均衡器这种 SPI 扩展点，选择结果只在本地生效：

- 本地拿到 `RandomLoadBalancer`
- 本地执行它的 `select(...)`

它不需要把“我本次用了 random”写到网络包里。

但序列化器不一样，它必须跨进程、跨网络一致，所以当前项目多了一层：

- SPI 扩展名
- 协议 type code

这也是为什么序列化器这条线必须有：

- `SerializerType`
- `SerializerFactory`

而负载均衡器那条线不需要协议类型码桥接。

### 21.13 现在可以怎样整体理解“序列化器从 SPI 落到协议头”

我目前对这条链的完整理解是：

当前项目里，序列化器先作为一个 `Serializer` SPI 扩展点存在，框架通过 `META-INF/rpc/...Serializer` 和 `ExtensionLoader` 管理“有哪些实现”和“默认实现是什么”；但因为序列化器最终要跨网络在 consumer 和 provider 之间达成一致，所以它不能只停留在本地扩展名层面，而必须通过实现类上的 `@SerializerType` 进一步绑定一个协议级 type code。随后 `SerializerFactory` 把“协议 type code”和“SPI 扩展实例”接起来，调用编排阶段把本次选择的序列化器收敛进 `RpcHeader.serializerType`，最终 encoder 和 decoder 都只依赖这个 type code 去查找真正的 `Serializer` 实现并完成 body 的编解码。因此，序列化器这条线本质上是“SPI 扩展机制 + 协议层类型码”的联合设计。

---

## 二十二、provider 优雅停机专项修正记录

前面已经把 provider 优雅停机的理论主线讲清楚了，但后面又专门按代码审了一遍当前实现。审完之后可以明确说：原始实现能表达“优雅停机”这个方向，但在顺序和竞态处理上确实有几个真实缺口，其中有些已经属于实际 bug，而不是单纯的设计不够优雅。

### 22.1 原始实现里最核心的问题：本地服务对象被摘得太早

在最初实现里，停机顺序是：

1. `stopAcceptingRequests()`
2. `unregisterAllServices()`
3. `awaitDrained(...)`
4. 再关监听和线程池

这在 [RpcNettyServer.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/netty/server/RpcNettyServer.java) 和 [RpcSocketServer.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/socket/legacy/server/RpcSocketServer.java) 里都存在。

问题在于 [LocalRegistryImpl.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/registry/local/LocalRegistryImpl.java) 的 `unregister(serviceName)` 不只是从注册中心摘地址，还会：

- `SERVICE_MAP.remove(serviceName)`

也就是说，它会把 provider 本地真正用于执行请求的服务对象映射一起删掉。

但同时，已经被入口放行、甚至已经开始执行 drain 的老请求，在 [RpcRequestExecutor.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestExecutor.java) 里真正执行业务时，仍然要通过：

- `localRegistry.getService(serviceName)`

去查本地服务对象。

这样就会产生一个真实错误场景：

1. 某个老请求在停机前已经进入 provider
2. provider 开始 shutdown
3. 提前执行 `unregisterAllServices()`
4. 本地 `SERVICE_MAP` 被清掉
5. 这个老请求后面真正执行时，取不到服务对象，直接失败

所以这个问题不是“优雅性不够”，而是：

- **老请求在优雅停机窗口内仍有机会因为本地服务映射被提前删除而失败**

这就是当前优雅停机实现里最实质的 bug。

### 22.2 第二个问题：监听端口关得太晚

原始实现里，provider 在：

- `awaitDrained(...)`

之前还没有关闭监听端口。

这意味着：

- Netty 版在 drain 窗口内仍然可以继续 accept 新连接
- Socket 版在 drain 窗口内 `ServerSocket.accept()` 也还开着

虽然应用层会通过：

- `RpcRequestDispatcher` 检查 `isAcceptingRequests()`

去返回 `503`

但这仍然会带来两个问题：

1. 停机期间还会继续消耗 accept/连接资源
2. 从语义上说，provider 已经决定下线了，却还保持监听端口敞开

所以更合理的顺序应该是：

- 先停止接新请求
- 尽快关监听端口
- 再等待存量请求 drain

### 22.3 第三个问题：只靠 inflight 统计不够，存在“已进入口但未 inflight”的竞态

原始实现的 drain 判断只依赖：

- `inflightRequests == 0`

也就是只看：

- 已经进入 `RpcRequestExecutor.execute(...)`
- 并调用了 `incrementInflight()`

的请求数。

但真实链路中存在一个窄窗口：

1. 请求已经通过网络层进来
2. 已经进入 `RpcRequestDispatcher.handleBusinessRequest(...)`
3. 但还没走到 `RpcRequestExecutor.incrementInflight()`

如果此时 shutdown 线程正在 `awaitDrained()`，它可能会看到：

- inflight 还是 0

于是误判为已经排空。

所以原始实现的问题是：

- **只统计真正执行业务的 inflight，不统计已经进入 provider 业务处理链但尚未执行到 bizExecutor 的活动请求**

### 22.4 第四个问题：`shutdown()` 没有显式幂等保护

这个问题单看一处代码不明显，但把调用路径串起来就看得到了。

当前 `RpcServer` 继承了 `AutoCloseable`，外部可以主动：

- `shutdown()`
- `close()`

而 [RpcNettyServer.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/netty/server/RpcNettyServer.java) 的 `start()` 又在 `finally` 里会调用一次 `shutdown()`。同时 [RpcProviderBootstrap.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/api/bootstrap/RpcProviderBootstrap.java) 的 `close()` 也会调用：

- `rpcServer.shutdown()`

这意味着重复调用路径是客观存在的。

如果没有幂等保护，就会出现：

- 重复摘注册
- 重复关 channel / socket
- 重复关线程池
- 重复关统计组件

这些操作未必每次都炸，但显然不是稳定的 shutdown 语义。

---

### 22.5 已完成修正一：先关监听，再 drain，再摘注册和本地服务映射

当前已经把 Netty 和 Socket 两条服务端实现的停机顺序都改成了：

1. `stopAcceptingRequests()`
2. 关闭监听端口 / 监听 channel
3. `awaitDrained(...)`
4. `unregisterAllServices()`
5. 关闭 event loop / executor / 统计资源

这样修正之后：

- 新连接不会在停机窗口内继续被 accept
- 老请求会尽量跑完
- 本地 `SERVICE_MAP` 不会在老请求还没执行完时被提前清掉

这是这次优雅停机收敛里最关键的修正。

### 22.6 已完成修正二：补一层 `activeRequests`，堵住 inflight 之前的竞态窗口

为了收掉“请求已进入 provider 业务处理链，但还没进 inflight”的窗口，当前在 [ServerLifecycle.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/runtime/server/ServerLifecycle.java) 里新增了一层：

- `activeRequests`

并增加了：

- `incrementActiveRequests()`
- `decrementActiveRequests()`
- `getActiveRequests()`

同时在 [RpcRequestDispatcher.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestDispatcher.java) 的业务请求入口 `handleBusinessRequest(...)` 最外层加了：

1. 进入时 `incrementActiveRequests()`
2. finally 里 `decrementActiveRequests()`

这意味着现在 provider 端有两层计数：

#### `activeRequests`

表示：

- 已经进入 provider 业务请求处理链
- 但未必已经真正进入 biz 线程池执行

#### `inflightRequests`

表示：

- 已经进入 `RpcRequestExecutor.execute(...)`
- 正在真正执行业务逻辑

对应地，`awaitDrained(...)` 现在等待的是：

- `activeRequests == 0`
- 且 `inflightRequests == 0`

这样 drain 判断就更准确了。

### 22.7 已完成修正三：给 `shutdown()` 增加幂等保护

当前已经在：

- [RpcNettyServer.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/netty/server/RpcNettyServer.java)
- [RpcSocketServer.java](D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/core/transport/socket/legacy/server/RpcSocketServer.java)

里增加了：

- `AtomicBoolean shutdownStarted`

并在 `shutdown()` 开头通过：

```java
if (!shutdownStarted.compareAndSet(false, true)) {
    return;
}
```

实现幂等保护。

这表示：

- 第一次 shutdown 正常执行完整停机流程
- 后续重复调用直接返回

这样当前服务端 shutdown 就具备了稳定的单次执行语义。

---

### 22.8 当前优雅停机链路收敛后的最终顺序

到现在为止，provider 优雅停机主线已经收敛成下面这条顺序：

1. `stopAcceptingRequests()`
2. 关闭监听端口 / 监听 channel
3. provider 入口不再接受新业务请求
4. `awaitDrained(...)` 等待：
   - `activeRequests == 0`
   - `inflightRequests == 0`
5. 再执行 `unregisterAllServices()`
   - 从注册中心摘除地址
   - 移除本地服务映射
6. 最后关闭 event loop、业务线程池、统计资源

这条顺序现在已经比较接近一个稳定的优雅停机实现了。

### 22.9 当前这条线已经解决了什么

这次收敛之后，当前优雅停机已经解决了以下问题：

1. 老请求不会因为本地服务映射被提前清掉而失败
2. 停机期间不会继续保持监听端口敞开
3. drain 判断不再只靠 inflight，而是覆盖入口级活动请求
4. `shutdown()` 重复调用不会再打乱顺序

所以当前这条线已经从“有真实缺口”变成了：

- 主流程合理
- 关键顺序正确
- 竞态窗口明显缩小
- 关闭语义更稳定

### 22.10 当前优雅停机仍然可以继续演进的点

虽然这条线已经比原始实现完整很多，但后面如果还要继续深挖，仍然有一些可以继续演进的点：

#### 1. `activeRequests` 和连接级状态是否还需要更细粒度观测

当前已经覆盖了业务入口和业务执行阶段，但还没有单独统计：

- 已建立但空闲的连接
- 已接收但尚未完整解码的消息

从当前项目复杂度来看，这不是必须，但从更严格的 server lifecycle 设计来看，这是可以继续往下做的。

#### 2. shutdown 过程中日志和 metrics 是否要进一步加强

例如可以考虑更明确输出：

- 进入停机时的 `activeRequests`
- `inflightRequests`
- drain 是否超时
- 最终强制关闭时剩余多少请求

这样后续排查停机体验会更方便。

#### 3. 是否要把 shutdown state 显式建模成状态机

当前只有一个：

- `shutdownStarted`

如果以后 provider 生命周期更复杂，也可以演进成更显式的：

- RUNNING
- STOPPING
- STOPPED

状态模型。

### 22.11 我对当前优雅停机实现的最终判断

当前项目的 provider 优雅停机，经过这次专项修正后，已经从“概念上想做优雅停机，但顺序和竞态上有真实缺口”，收敛成了一条更稳定的实现：先停接新请求、再关监听、再等待入口活动请求和真正执行中的请求排空、最后才摘注册和回收资源，同时 `shutdown()` 本身也具备了幂等保护。对当前项目规模来说，这已经是一条比较扎实的优雅停机主线了。
