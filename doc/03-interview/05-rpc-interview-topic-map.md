# RPC 项目面试追问映射表

## 1. 这份文档的用途

这份文档不是用来背答案的，而是帮你建立一个映射关系：

这个 RPC 项目分别能对应到哪些常见面试知识点。

你可以把它理解成：

- 项目经历 -> 面试追问方向 -> 你该展开什么

这样你在面试时，就不会只停留在项目本身，而是能顺势把回答扩展到常见八股和设计问题上。

---

## 2. 项目结构重构 -> 软件设计 / 架构能力

### 可能追问

1. 你怎么做分层设计
2. 你为什么要拆这些层
3. 高内聚低耦合在这个项目里怎么体现
4. 如果不这样拆会有什么问题

### 你可以展开的点

1. 单一职责
2. 分层架构
3. 依赖方向控制
4. 工厂模式
5. 策略模式
6. 外观模式

### 项目对应内容

1. `config / bootstrap / invoke / transport / protocol / registry / discovery`
2. `RpcTransportFactory / RpcServerFactory`
3. `ClusterInvoker`
4. `FilterManager`

---

## 3. 动态代理 -> Java 反射 / 代理机制

### 可能追问

1. JDK 动态代理和 CGLIB 区别
2. 什么时候用 JDK 代理，什么时候用 CGLIB
3. 动态代理底层原理
4. 反射调用有没有性能问题

### 你可以展开的点

1. JDK 动态代理要求接口
2. CGLIB 基于继承
3. `InvocationHandler / MethodInterceptor`
4. 反射与方法缓存

### 项目对应内容

1. `RpcProxyFactory`
2. `RpcInvocationHandler`
3. `RpcMethodInterceptor`

---

## 4. 服务注册发现 -> ZooKeeper

### 可能追问

1. 为什么选 ZooKeeper
2. 临时节点有什么作用
3. watcher（观察器）是不是一次性的
4. 注册和发现为什么要拆开
5. 为什么要做本地缓存

### 你可以展开的点

1. CP 特性
2. 临时节点自动下线
3. watcher 一次性注册
4. 订阅发现模型
5. 本地目录缓存与 fallback（回退）

### 项目对应内容

1. `ZooKeeperRegistryImpl`
2. `ServiceDirectory`
3. `ServiceDiscovery`
4. `ServiceChangeListener`

---

## 5. 统一协议 -> 网络协议设计

### 可能追问

1. 为什么要有协议头
2. `requestId` 有什么用
3. `serializerType` 为什么要放进消息头
4. `magicNumber` 有什么用
5. 你现在协议还能怎么继续扩展

### 你可以展开的点

1. 包头设计
2. 请求响应关联
3. 版本兼容
4. 编解码边界
5. 协议演进

### 项目对应内容

1. `RpcHeader`
2. `RpcMessage`
3. `RpcProtocolEncoder`
4. `RpcProtocolDecoder`
5. `SocketMessageCodec`

---

## 6. Netty 传输 -> Netty / IO 模型

### 可能追问

1. Netty 相比 BIO（阻塞式 IO）的优势
2. 连接池为什么要有
3. `requestId` 和 `future（异步结果占位）` 是怎么配合的
4. 心跳和重连怎么做的
5. 为什么业务执行不能放在 IO 线程

### 你可以展开的点

1. Reactor 模型
2. Channel / EventLoop
3. 异步回调与 future
4. 心跳探活
5. 线程模型隔离

### 项目对应内容

1. `RpcNettyClient`
2. `ConnectionPool`
3. `RequestManager`
4. `RpcClientHandler`
5. `ReconnectHandler`
6. `RpcNettyServer`
7. `RpcRequestExecutor`

---

## 7. Socket 传输 -> 最小网络实现 / 对照设计

### 可能追问

1. 为什么还保留 Socket
2. Socket 和 Netty 在项目里的角色区别
3. 你怎么做到两种 transport 可切换

### 你可以展开的点

1. 最小实现
2. 上层复用、底层替换
3. 工厂模式与抽象层

### 项目对应内容

1. `RpcSocketClient`
2. `RpcSocketServer`
3. `RpcTransportFactory`
4. `RpcServerFactory`

---

## 8. SPI 扩展 -> 设计模式 / 框架设计

### 可能追问

1. 为什么不用 if/else 切实现
2. 你的 SPI 和 JDK `ServiceLoader` 有什么区别
3. 扩展实例为什么要缓存
4. 为什么要支持默认扩展

### 你可以展开的点

1. 策略模式
2. 工厂 + 注册表模式
3. 命名扩展
4. 扩展生命周期

### 项目对应内容

1. `SPI`
2. `ExtensionLoader`
3. `ExtensionFactory`
4. `SerializerFactory`
5. `LoadBalancerFactory`

---

## 9. 配置系统 -> 工程化 / 配置分层设计

### 可能追问

1. 为什么要拆多个 binder
2. Spring Boot 配置怎么和 core 配置衔接
3. 方法级配置怎么覆盖全局配置
4. 配置优先级怎么理解

### 你可以展开的点

1. 配置读取层和绑定层分离
2. 总配置与运行期配置分离
3. 方法级覆盖模型
4. 适配层思想

### 项目对应内容

1. `RpcConfigLoader`
2. `RpcPropertySource`
3. `RpcFrameworkConfigBinder`
4. `RpcBootFrameworkProperties`
5. `MethodConfig`

---

## 10. Filter 链 -> AOP / 责任链模式

### 可能追问

1. 为什么要引入 Filter 链
2. 为什么分 consumer / invoker / provider 三段
3. 如果不用 Filter 会怎样
4. Filter 顺序怎么控制

### 你可以展开的点

1. 责任链模式
2. 横切逻辑收口
3. 调用阶段分层
4. 可扩展性

### 项目对应内容

1. `RpcFilter`
2. `FilterPhase`
3. `DefaultFilterChain`
4. `FilterManager`

---

## 11. 限流 / 熔断 / 降级 -> 微服务治理

### 可能追问

1. 限流和熔断区别
2. 熔断和降级区别
3. 为什么 provider 也要有限流
4. 为什么做方法级熔断
5. 重试和重连区别

### 你可以展开的点

1. 请求前保护
2. 请求后恢复
3. 保护调用方 vs 保护被调方
4. consumer/provider 双侧治理

### 项目对应内容

1. `RetryExecutor`
2. `CircuitBreakerImpl`
3. `RateLimiterManager`
4. `DegradationPolicyFactory`
5. `ConsumerCircuitBreakerFilter`
6. `ProviderRateLimitFilter`

---

## 12. 线程池隔离与优雅停机 -> 并发 / 生命周期管理

### 可能追问

1. 为什么业务线程池要和 IO 线程分开
2. 你怎么做优雅停机
3. inflight 请求计数有什么用
4. 为什么停机要先摘注册再关服务

### 你可以展开的点

1. 线程池隔离
2. 背压与过载
3. 生命周期管理
4. 优雅停机顺序

### 项目对应内容

1. `BizThreadPool`
2. `ServerLifecycle`
3. `RpcRequestExecutor`
4. `RpcNettyServer`
5. `RpcSocketServer`

---

## 13. Spring Boot 接入 -> Spring 生命周期 / 自动装配

### 可能追问

1. `@RpcReference` 怎么注入的
2. `@RpcService` 怎么发布的
3. 为什么要做 starter
4. Spring 和 core 怎么解耦

### 你可以展开的点

1. `BeanPostProcessor`
2. `SmartLifecycle`
3. 自动装配
4. 配置绑定与适配层

### 项目对应内容

1. `RpcSpringManager`
2. `RpcSpringRegistrar`
3. `RpcSpringBootAutoConfiguration`
4. `RpcBootFrameworkProperties`

---

## 14. 你在面试里该怎么用这张映射表

最简单的方式是：

1. 先用项目把话题引出来
2. 看到面试官往哪个方向追问，就切到对应知识点
3. 回答时始终把“项目里的实际实现”带上

这样你的回答不会只像背八股，而是“项目经验 + 原理理解”一起说。

---

## 15. 小结

这张映射表的核心作用是：

让你的 RPC 项目不只是“一个项目经历”，而是能自然连接到：

1. Java 基础
2. Netty
3. ZooKeeper
4. Spring
5. 设计模式
6. 微服务治理

也就是说，你可以用这一个项目，撑起相当多方向的面试追问。
