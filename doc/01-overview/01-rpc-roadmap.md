# RPC 项目改造路线图

## 1. 目标

这份路线图的目标不是继续把项目堆成“功能越来越多”的状态，而是先把框架骨架搭清楚，让后续优化有稳定落点。

这轮改造优先解决下面几个问题：

1. 分层不清，调用链容易跨层耦合。
2. 外部接入方式偏手工，使用成本高。
3. 配置入口不统一，很多能力只能靠代码写死。
4. 治理能力缺少统一扩展点，后续越改越乱。
5. Spring（应用框架）/Spring Boot（快速启动框架）场景没有真正接住核心配置。

## 2. 改造原则

后续所有调整尽量遵循这些原则：

1. `transport`（传输层）只负责连接、编解码、收发、心跳、重连。
2. `registry/discovery`（注册/发现）只负责注册、订阅、地址变化。
3. `proxy/invocation/filter/cluster`（代理/调用编排/过滤器/集群）负责请求编排、治理和扩展。
4. `bootstrap/config`（启动/配置）负责外部接入体验和生命周期收口。
5. 可替换能力优先抽成 SPI（可插拔扩展点），可调参数优先进入配置。

建议依赖方向保持为：

`bootstrap（启动） -> config（配置） -> filter/proxy/invocation（过滤器/代理/调用编排） -> transport/registry（传输/注册）`

## 3. 阶段拆分

### P0：先补齐基础骨架

目标：先让框架有清晰的结构边界，而不是继续在旧类上横向堆逻辑。

内容包括：
1. 服务发现从注册职责中拆出，支持订阅、本地缓存、预热和 stale fallback（过期兜底）。
2. 增加统一 filter（过滤器）链和 `RpcContext`。
3. 增加请求级容错和方法级治理入口。
4. provider（提供端）侧做业务线程池隔离和优雅停机。

### P1：提升接入体验和治理基础

目标：让外部接入从“手工拼装”收敛到“配置 + 注解 + bootstrap（启动器）/Spring（应用框架）”。

内容包括：
1. 方法级配置模型。
2. `@RpcService / @RpcReference`。
3. `rpc-spring`。
4. `rpc-spring-boot-starter`。
5. 基础可观测性、MDC（日志上下文）、metrics（指标）。

### P2-1：先补运行时保护能力

目标：先具备基本的线上保护能力，再考虑更高级的优化。

内容包括：
1. consumer（消费端）/provider（提供端）两侧限流。
2. consumer（消费端）/provider（提供端）两侧降级。
3. consumer（消费端）侧熔断。
4. 方法级治理作用域。
5. filter（过滤器）治理扩展点收口。

### P2-2 及以后

这些属于后续增强，不是当前这轮骨架改造的重点：

1. 压缩
2. 泛化调用
3. 异步调用
4. 更复杂的 provider（提供端）fallback（回退）策略
5. 更强的组合测试和 benchmark（基准测试）

## 4. 当前已完成状态

### 4.1 P0 已完成

已经完成：
1. `ServiceDiscovery`（服务发现）、本地缓存、预热、stale fallback（过期兜底）。
2. `Filter`（过滤器）链、`RpcContext`、trace（链路追踪）/metrics（指标）透传。
3. `InvocationOptions / MethodConfig / ClusterInvoker`（调用选项/方法配置/集群调用器）。
4. provider（提供端）线程池隔离、优雅停机、生命周期控制。

### 4.2 P1 已完成主干

已经完成：
1. 方法级配置模型。
2. `@RpcService / @RpcReference`。
3. bootstrap（启动器）自动扫描和注入。
4. `rpc-spring`。
5. `rpc-spring-boot-starter`。
6. 基础可观测性。

### 4.3 P2-1 已完成主体

已经完成：
1. consumer（消费端）服务级/方法级限流。
2. consumer（消费端）服务级/方法级熔断。
3. consumer（消费端）降级。
4. provider（提供端）限流。
5. provider（提供端）降级。
6. filter（过滤器）扩展从隐式发现收成命名式 SPI（可插拔扩展点）。
7. filter（过滤器）支持配置启停和顺序覆盖。
8. Spring Boot（快速启动框架）下 `rpc.*` 已能绑定成 `RpcFrameworkConfig`。
9. consumer（消费端）/provider（提供端）两侧降级策略已配置化，支持：
   - `failFast`（快速失败）
   - `defaultValue`（默认值回退）

## 5. 当前项目的骨架形态

如果从“基础框架有没有搭起来”这个角度看，现在项目已经具备下面这些基础结构：

### 5.1 核心层次

1. `config`（配置）
2. `bootstrap`（启动）
3. `filter`（过滤器）
4. `proxy`（代理）
5. `invocation`（调用编排）
6. `cluster`（集群容错）
7. `transport`（传输）
8. `registry/discovery`（注册/发现）
9. `spring / spring-boot-starter`

### 5.2 扩展点

当前已经比较明确的扩展点包括：
1. serializer SPI（序列化扩展点）
2. load balancer SPI（负载均衡扩展点）
3. filter SPI（过滤器扩展点）
4. degradation policy（降级策略）工厂入口

### 5.3 配置入口

当前已经基本统一到：
1. `rpc.properties`
2. JVM（Java 虚拟机）`-D`
3. Spring Boot（快速启动框架）`application.yml / application.properties`

## 6. 当前建议的使用方式

### 6.1 Provider（提供端）基础配置

```properties
rpc.transport=netty
rpc.registry.type=zookeeper
rpc.registry.address=127.0.0.1:2181

rpc.server.host=127.0.0.1
rpc.server.port=8080
rpc.server.scanPackages=com.rpc.provider
rpc.server.autoRegisterAnnotatedServices=true
```

### 6.2 Consumer（消费端）基础配置

```properties
rpc.transport=netty
rpc.serializer=protobuf
rpc.loadbalancer=random

rpc.client.readTimeout=3000
rpc.client.retryTimes=2
rpc.client.cluster=failover
```

### 6.3 Filter（过滤器）配置

```properties
rpc.filter.consumer=trace,mdc,consumerMetrics
rpc.filter.invoker=consumerCircuitBreaker
rpc.filter.provider=providerRateLimit,providerMdc,providerMetrics
rpc.filter.order.providerRateLimit=5
```

### 6.4 降级配置

```properties
rpc.client.enableDegradation=true
rpc.client.degradation.policy=defaultValue
rpc.client.degradation.defaultValue.com.rpc.HelloService#sayHello=consumer-fallback

rpc.server.degradation.enabled=true
rpc.server.degradation.policy=defaultValue
rpc.server.degradation.defaultValue.com.rpc.HelloService#sayHello=provider-fallback
```

## 7. 当前还没做，但属于下一轮的事情

如果继续按“先把框架骨架彻底收口，再谈优化”的思路推进，下一轮建议优先做：

1. 把 Spring Boot（快速启动框架）下的方法级配置绑定做得更自然。
2. 补更完整的组合测试：
   - `netty + socket`
   - `provider（提供端）/consumer（消费端）同时开启治理`
   - `filter（过滤器）启停组合`
   - `defaultValue（默认值回退）降级`
3. provider（提供端）侧补更丰富的 fallback（回退）策略，不只停留在 `failFast/defaultValue`。
4. 再决定哪些部分进入性能或工程优化。

## 8. 结论

当前这轮改造的重点不是“把所有功能一次做满”，而是把项目从“代码混乱、功能散落”的状态，推进到“有清晰骨架、可继续演进”的状态。

从这个目标来看，目前已经基本达成：
1. 分层清晰了。
2. 调用链清楚了。
3. 配置入口基本统一了。
4. 运行时治理开始统一了。
5. Spring（应用框架）/Spring Boot（快速启动框架）接入也接住了核心能力。

接下来更合适的做法不是继续无节制加功能，而是基于这套骨架做阶段性验证，然后再决定优化优先级。
