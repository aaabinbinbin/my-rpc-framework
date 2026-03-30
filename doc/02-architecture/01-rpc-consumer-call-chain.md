# RPC 消费端调用链详解

## 1. 文档目的

这份文档专门解释“消费端（consumer，服务调用方）发起一次 RPC 调用时，框架内部到底经过了哪些步骤”。

它关注的是：

1. 业务代码如何从本地方法调用进入 RPC 框架
2. 代理层（proxy，代理）如何把调用转换成请求
3. 过滤器链（filter chain，过滤器链）和治理逻辑在哪一层生效
4. 服务目录（service directory，服务目录）、负载均衡（load balancer，负载均衡器）、集群容错（cluster，集群容错）分别做什么
5. 传输层（transport，传输层）如何把请求发出去

建议配合下面这些类一起阅读：

1. `RpcConsumerBootstrap`
2. `RpcProxyFactory`
3. `RpcInvocationHandler`
4. `RpcClientInvocationExecutor`
5. `FilterManager`
6. `ServiceDirectory`
7. `RpcServiceResolver`
8. `ClusterInvoker`
9. `RpcNettyClient` 或 `RpcSocketClient`

---

## 2. 总体调用链

一次消费端调用，大致按下面这条链路往下走：

1. 业务代码从 `RpcConsumerBootstrap` 拿到服务代理
2. 代理对象拦截方法调用
3. 代理层把本地调用转换成 `RpcRequest`
4. 调用选项解析器计算本次调用的生效配置
5. `CONSUMER` 阶段过滤器链执行
6. `INVOKER` 阶段过滤器链执行
7. 服务目录返回当前可用实例快照
8. 负载均衡器从候选地址中选出目标地址
9. 集群容错层决定是否重试、是否切换实例
10. 传输层把请求编码后发送到网络
11. 收到响应后反序列化为 `RpcResponse`
12. 返回结果或抛出异常给业务代码

这条链之所以要拆层，是为了让“代理、治理、服务发现、传输”各自职责明确，避免把所有逻辑堆进一个类里。

---

## 3. 调用入口：RpcConsumerBootstrap

### 3.1 它负责什么

`RpcConsumerBootstrap` 是消费端高层入口，负责把下面这些组件组装起来：

1. 配置对象
2. 运行时治理配置
3. 服务发现（service discovery，服务发现）
4. 传输客户端
5. 代理工厂

因此，业务代码不应该自己手动去 `new` 注册中心、发现器、传输客户端和代理工厂。  
这些组装动作都应该收口在 bootstrap（启动器）里。

### 3.2 为什么 bootstrap 要比 transport 更高一层

`RpcTransport` 只负责“把请求发出去”。  
但真正的消费端启动过程还包括：

1. 从配置创建治理策略
2. 初始化过滤器链
3. 连接服务发现
4. 选择具体传输实现
5. 创建代理对象

这些都属于“启动装配”，不属于“网络发送”。

---

## 4. 代理层：把本地调用转换成远程请求

### 4.1 代理层真正做什么

代理层的核心工作，是把本地 Java 方法调用转换成一个可跨进程传输的请求对象。

它会从当前调用里收集这些信息：

1. `serviceName（服务名）`
2. `methodName（方法名）`
3. 参数类型
4. 参数值
5. 返回值类型
6. `attachments（附加字段）`

最后组装成 `RpcRequest`。

### 4.2 attachments 为什么重要

`attachments（附加字段）` 不是业务参数，而是横切元数据。  
当前主要承载：

1. `requestId（请求标识）`
2. `traceId（链路标识）`
3. 方法级超时覆盖
4. 方法级序列化器覆盖
5. 方法级负载均衡器覆盖
6. 其他过滤器透传信息

这让横切信息不需要硬塞进业务方法签名里。

---

## 5. RpcContext：线程级上下文

### 5.1 为什么需要它

如果没有 `RpcContext`，很多横切数据只能沿着方法参数一层层传递，代码会迅速变乱。  
所以框架用 `ThreadLocal` 维护当前线程的 RPC 上下文。

它主要保存：

1. `requestId（请求标识）`
2. `traceId（链路标识）`
3. `attachments（附加字段）`

### 5.2 它在调用链里的位置

通常在发起调用之前创建或获取当前上下文，然后由代理层和过滤器链共同读写。  
传输层不直接承担这类业务上下文维护职责。

---

## 6. 方法级配置如何生效

### 6.1 为什么只靠全局配置不够

同一个服务里的不同方法，治理需求经常不同。比如：

1. 某个读方法超时要更短
2. 某个写方法不允许自动重试
3. 某个热点方法需要单独限流
4. 某个方法需要按方法维度熔断

所以框架支持方法级配置。

### 6.2 当前可覆盖哪些项

当前方法级配置主要支持：

1. `retryTimes（重试次数）`
2. `clusterStrategy（集群策略）`
3. `readTimeout（读取超时）`
4. `serializerName（序列化器名称）`
5. `loadBalancerName（负载均衡器名称）`
6. `rateLimitEnabled（限流开关）`
7. `rateLimitPermitsPerSecond（每秒许可数）`
8. `circuitBreakerScope（熔断作用域）`

解析结果会收口到 `InvocationOptions`，由后续调用链读取。

---

## 7. 过滤器链：统一横切入口

### 7.1 为什么消费端有两段过滤器链

当前消费端不是只有一段过滤器链，而是拆成了：

1. `CONSUMER`
2. `INVOKER`

原因是不同横切逻辑所处时机不同：

1. 链路标识透传、日志上下文恢复，适合更靠近代理层
2. 熔断、降级、发送前最后一跳检查，更适合靠近真正发请求之前

### 7.2 当前重要的消费端过滤器

主要包括：

1. `TraceFilter`
2. `MdcFilter`
3. `ConsumerMetricsFilter`
4. `ConsumerCircuitBreakerFilter`

它们分别负责：

1. 透传 `traceId（链路标识）`
2. 把关键字段写入 `MDC（日志映射上下文）`
3. 记录调用耗时与成败
4. 在进入传输层之前做熔断与降级判断

---

## 8. ConsumerCircuitBreakerFilter：熔断与降级

### 8.1 为什么它放在 invoker 阶段

熔断过滤器既不能放到最底层传输里，也不适合放到最外层代理里。  
它需要在“已经知道本次调用配置、但还没真正发请求”的位置工作，这正是 invoker（调用执行器）阶段。

### 8.2 它会做什么

1. 计算熔断键
2. 判断当前是否允许继续放行
3. 不允许时直接走降级逻辑
4. 请求结束后记录成功或失败

### 8.3 为什么支持服务级和方法级两种作用域

因为有的场景希望整服务一起保护，有的场景只想隔离一个坏方法。  
所以当前支持：

1. 服务级：`serviceName（服务名）`
2. 方法级：`serviceName（服务名）#methodName（方法名）`

---

## 9. ServiceDirectory：服务目录与本地快照

### 9.1 为什么不能每次调用都直接查注册中心

如果每次发请求前都去查 ZooKeeper（分布式协调服务），会有几个问题：

1. 请求链路变长
2. 注册中心抖动会直接影响调用成功率
3. 性能和稳定性都不好

所以当前消费端改成了：

1. 首次订阅
2. 本地缓存快照
3. 变更时由 watcher（观察器）更新

### 9.2 它负责什么

`ServiceDirectory` 负责：

1. 懒加载订阅服务
2. 维护服务地址快照
3. 注册中心变更时更新本地目录
4. 处理缓存 TTL（生存时间）
5. 发现失败时决定是否回退到旧快照
6. 启动时预热关键服务

---

## 10. RpcServiceResolver 与负载均衡

`RpcServiceResolver` 的职责是：

1. 从 `ServiceDirectory` 获取可用实例列表
2. 结合负载均衡器选出目标地址
3. 在需要时结合实例级熔断状态进行筛选

它只负责“本次请求打给谁”，不负责真正发请求。

当前支持的负载均衡器包括：

1. `random（随机）`
2. `roundRobin（轮询）`
3. `leastConnections（最少连接）`
4. `consistentHash（一致性哈希）`

---

## 11. Cluster：失败后怎么办

### 11.1 为什么它要单独成层

地址选择和失败处理不是一回事：

1. 负载均衡负责决定第一次打到哪个实例
2. 集群容错负责决定失败后是否重试、是否换实例

所以 cluster（集群容错）应该独立成层。

### 11.2 当前支持的策略

当前主要支持：

1. `failFast（快速失败）`
2. `failOver（失败转移）`

其中 `RetryExecutor` 负责请求级重试，和连接级重连是两套不同机制。

---

## 12. 传输层：Netty 与 Socket

当前消费端可按配置选择：

1. `RpcNettyClient`
2. `RpcSocketClient`

两者的共同点是：

1. 都复用同一套上层代理、治理、服务发现和集群容错逻辑
2. 只在底层网络收发方式上不同

区别在于：

1. `Netty（网络通信框架）` 版本是异步事件驱动模型
2. `Socket（套接字）` 版本是更直接的阻塞式实现

---

## 13. Netty 客户端详细步骤

当传输实现是 `RpcNettyClient` 时，请求大致这样流动：

1. 构造协议消息
2. `RequestManager` 以 `requestId（请求标识） -> future（异步结果占位）` 形式挂起请求
3. 从连接池拿到可用 `channel（网络通道）`
4. 编码后写出到网络
5. 收到响应后由客户端处理器解析
6. `RequestManager` 完成对应 `future（异步结果占位）`
7. 上层线程拿到结果继续返回

---

## 14. Socket 客户端详细步骤

当传输实现是 `RpcSocketClient` 时，请求路径更直接：

1. 建立 `Socket（套接字）`
2. 手工编码消息头和消息体
3. 写出到输出流
4. 从输入流阻塞读取响应
5. 解码并返回结果

它更适合帮助理解 RPC 最小工作链路。

---

## 15. 建议的源码阅读顺序

如果你现在要顺着消费端主链路读源码，建议按这个顺序：

1. `RpcConsumerBootstrap`
2. `RpcProxyFactory`
3. `RpcInvocationHandler`
4. `DefaultInvocationOptionsResolver`
5. `FilterManager`
6. `ConsumerCircuitBreakerFilter`
7. `ServiceDirectory`
8. `RpcServiceResolver`
9. `ClusterInvokerFactory`
10. `RetryExecutor`
11. `RpcNettyClient`

---

## 16. 小结

当前消费端已经形成了比较清晰的分层骨架：

1. bootstrap（启动器）负责组装
2. proxy（代理层）负责把本地调用转成远程请求
3. invocation（调用编排层）负责治理与调用选项解析
4. discovery（服务发现层）负责维护实例快照
5. cluster（集群容错层）负责失败处理
6. transport（传输层）负责网络发送

这样做的好处是：后续继续扩展重试、限流、熔断、Spring 接入时，不需要重新打散主链路。
