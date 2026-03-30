# RPC 服务端调用链详解

## 1. 文档目的

这份文档解释“服务端（provider，服务提供方）收到一次 RPC 请求后，框架内部是怎么处理的”。

重点包括：

1. 服务端如何启动
2. 服务对象如何注册
3. 请求如何进入执行链
4. 过滤器、线程池、反射调用分别位于什么位置
5. 为什么服务端要有生命周期控制和优雅停机

建议配合这些类一起阅读：

1. `RpcProviderBootstrap`
2. `LocalRegistryImpl`
3. `RpcNettyServer`
4. `RpcRequestDispatcher`
5. `RpcRequestExecutor`
6. `RpcRequestHandler`
7. `ServerLifecycle`
8. `ProviderRateLimitFilter`
9. `ProviderMetricsFilter`
10. `ProviderMdcFilter`

---

## 2. 总体处理流程

服务端处理一条请求，大致会经过这些步骤：

1. 启动时注册本地服务
2. 把服务地址发布到注册中心
3. 启动传输服务端监听端口
4. 接收网络请求并解码
5. 判断是心跳还是业务请求
6. 业务请求进入服务端过滤器链
7. 把真正的业务执行丢给业务线程池
8. 从本地注册表找到目标服务对象
9. 通过反射调用方法
10. 组装 `RpcResponse`
11. 编码并回写给客户端

---

## 3. 启动入口：RpcProviderBootstrap

### 3.1 它负责什么

`RpcProviderBootstrap` 是服务端高层入口，负责组装：

1. provider（服务提供端）运行时治理配置
2. 注册中心连接
3. 服务端传输实现
4. 服务扫描与发布

因此，业务代码不应该自己手动分别处理服务发布、注册中心交互和传输层启动。

### 3.2 当前支持两种服务注册方式

1. 手动 `registerService`
2. 按配置扫描 `@RpcService`

手动注册更适合非 Spring（应用框架）场景。  
注解扫描更适合 Spring / Spring Boot（应用启动框架）场景。

---

## 4. LocalRegistry：本地服务注册表

### 4.1 它解决什么问题

外部注册中心只保存“服务地址”，不会保存“JVM 内的服务对象实例”。  
但服务端真正执行业务方法时，需要拿到本地对象。

所以 `LocalRegistryImpl` 负责维护：

`serviceName（服务名） -> serviceInstance（服务实例）`

### 4.2 为什么它和 ServiceRegistry 要分开

两者职责完全不同：

1. `LocalRegistry` 管本地内存里的服务对象
2. `ServiceRegistry` 管外部注册中心里的服务地址

只有把这两层拆开，服务端链路才不会混乱。

---

## 5. 传输服务端：Netty 与 Socket

当前服务端可以按配置选择：

1. `RpcNettyServer`
2. `RpcSocketServer`

二者复用的上层结构包括：

1. 本地注册表
2. 请求分发器
3. 请求执行器
4. 生命周期控制

区别只在底层网络收发模型。

---

## 6. Netty 服务端入口

在 `Netty（网络通信框架）` 服务端，请求首先进入 `pipeline（处理流水线）`。  
典型顺序是：

1. 协议编解码处理器
2. 心跳相关处理器
3. `RpcRequestHandler`

`RpcRequestHandler` 的职责不是执行业务，而是把已经解码完成的协议消息交给更高层的请求分发器。

---

## 7. RpcRequestDispatcher：先分类型

不是所有进来的消息都是业务请求，还可能有：

1. 心跳消息
2. 其他协议控制消息

所以 `RpcRequestDispatcher` 负责先判断消息类型，再决定走哪条路径。

它主要做三件事：

1. 判断当前服务端是否还接受新请求
2. 区分心跳与业务请求
3. 把业务请求交给 `RpcRequestExecutor`

---

## 8. RpcRequestExecutor：业务执行主入口

### 8.1 为什么不能直接在 IO 线程里执行业务

如果直接在 `IO（输入输出）` 线程里做业务反射调用，会有明显风险：

1. 慢请求会阻塞网络读写
2. 一个热点请求可能拖垮整个服务端吞吐
3. 后续无法做线程池隔离与治理

所以当前服务端把真正的业务执行放在独立业务线程池中。

### 8.2 它负责什么

`RpcRequestExecutor` 主要负责：

1. 把请求提交到业务线程池
2. 维护 `inflight（正在执行中的）` 请求计数
3. 构造 provider（服务提供端）过滤器上下文
4. 执行 provider 过滤器链
5. 执行反射调用
6. 组装 `RpcResponse`

---

## 9. Provider 过滤器链

### 9.1 为什么它和消费端过滤器不一样

服务端过滤器链的目标不是“准备发请求”，而是：

1. 保护服务端自身
2. 恢复链路上下文
3. 记录服务端观测指标

### 9.2 当前关键过滤器

1. `ProviderRateLimitFilter`
2. `ProviderMdcFilter`
3. `ProviderMetricsFilter`

它们分别负责：

1. 对 `service#method` 维度做限流
2. 恢复 `MDC（日志映射上下文）`
3. 记录服务端调用耗时与成败

---

## 10. 反射调用：从 serviceName 找到对象

服务端收到请求时，拿到的不是 Java 直接引用，而是：

1. `serviceName（服务名）`
2. `methodName（方法名）`
3. 参数类型
4. 参数值

所以执行步骤通常是：

1. 从 `LocalRegistry` 用 `serviceName（服务名）` 找到服务对象
2. 根据 `methodName（方法名）` 和参数类型找到目标方法
3. 通过反射执行

这也是为什么服务端必须有本地注册表，而不能只依赖注册中心。

---

## 11. Provider 限流与降级

### 11.1 为什么服务端也要限流

消费端限流只能保护消费端自己，无法保护真正提供服务的一方。  
如果服务端没有限流，仍然可能出现：

1. 某个热点方法把线程池打满
2. 整个服务端响应时间急剧上升
3. 其他正常请求被拖垮

所以 provider（服务提供端）也必须具备限流能力。

### 11.2 当前策略

当前 `ProviderRateLimitFilter` 的行为是：

1. 按 `service#method` 做限流
2. 未超限时放行
3. 超限时根据配置决定直接拒绝，还是走降级策略

这说明服务端保护能力不只是“报错”，也可以选择“返回兜底结果”。

---

## 12. 生命周期与优雅停机

### 12.1 为什么不是直接 close

服务端停机如果只是简单 `close()`，会有几个问题：

1. 注册中心里还留着旧地址
2. 新请求可能继续打进来
3. 正在执行的请求会被直接打断

所以当前服务端有一套明确的优雅停机流程。

### 12.2 当前停机顺序

1. 标记不再接受新请求
2. 从注册中心摘除服务地址
3. 等待 `inflight（正在执行中的）` 请求排空
4. 关闭业务线程池
5. 关闭 `server socket（服务端套接字）` 或 `event loop（事件循环）`

这个流程由 `ServerLifecycle` 配合服务端实现完成。

---

## 13. Provider 侧日志与指标

### 13.1 为什么要恢复 MDC

如果只有消费端有 `traceId（链路标识）`，而服务端日志没有恢复上下文，那就无法把一条请求在两端串起来。

所以当前服务端会恢复至少这些字段：

1. `rpcRequestId（RPC 请求标识）`
2. `rpcTraceId（RPC 链路标识）`
3. `rpcService（RPC 服务名）`
4. `rpcMethod（RPC 方法名）`

### 13.2 当前有哪些指标

当前服务端主要记录两类指标：

1. 方法级调用指标
2. 运行时线程池与请求状态指标

这样后续排查慢请求、限流、线程池堆积时更容易定位问题。

---

## 14. Socket 服务端链路

`Socket（套接字）` 版本尽量复用和 `Netty（网络通信框架）` 相同的上层结构。  
区别主要是：

1. Netty 是异步事件驱动
2. Socket 是更直接的阻塞式 `ServerSocket（服务端套接字）`

因此 Socket 版很适合作为“最小服务端实现”的阅读入口。

---

## 15. 建议的源码阅读顺序

如果你要顺着服务端主链路读源码，建议按这个顺序：

1. `RpcProviderBootstrap`
2. `LocalRegistryImpl`
3. `RpcServerFactory`
4. `RpcNettyServer`
5. `RpcRequestHandler`
6. `RpcRequestDispatcher`
7. `RpcRequestExecutor`
8. `ProviderRateLimitFilter`
9. `ProviderMdcFilter`
10. `ProviderMetricsFilter`
11. `ServerLifecycle`

---

## 16. 小结

当前服务端已经不是“收到请求直接反射调用”的简单结构，而是形成了比较清晰的骨架：

1. bootstrap（启动器）负责装配
2. registry（注册中心层）负责地址发布
3. transport（传输层）负责网络收发
4. dispatcher（分发层）负责消息分类
5. executor（执行层）负责业务线程池与反射调用
6. filter（过滤器层）负责限流、日志、指标
7. runtime（运行时层）负责生命周期和优雅停机

这样后续继续扩展认证、更多治理策略或线程池隔离时，不需要重新拆散服务端主链路。
