# 项目模块细节文档

本文档按“模块职责 -> 关键代码 -> 实现机制 -> 设计原因 -> 边界处理 -> 面试问答”的方式讲解当前 RPC 项目。阅读目标不是背目录，而是能解释一次 RPC 调用在每个模块里为什么这样流转、出了异常如何兜底、哪些设计是为了稳定性和高并发。

## 0. 总体模块图

当前项目可以拆成 6 个主要 Maven 模块：

```text
example-api                 服务契约模块
example-provider            服务提供方示例
example-consumer            服务消费方示例
rpc-core                    RPC 核心能力，不依赖 Spring
rpc-spring                  Spring 容器集成
rpc-spring-boot-starter     Spring Boot 自动装配与配置绑定
```

核心调用路径：

```text
consumer 业务代码
  -> @RpcReference 注入的代理对象
  -> RpcInvocationHandler / RpcMethodInterceptor
  -> consumer filter 链：trace、MDC、metrics
  -> RpcClientInvocationExecutor
  -> invoker filter 链：consumer circuit breaker
  -> ServiceDirectory / ServiceDiscovery / LoadBalancer
  -> RpcNettyClient / ConnectionPool / RequestManager
  -> Netty 编码发送
  -> provider Netty 解码
  -> RpcRequestHandler
  -> BizThreadPool
  -> RpcRequestDispatcher / RpcRequestExecutor
  -> provider filter 链：providerMdc、providerMetrics、providerRateLimit
  -> LocalRegistry 取服务实例
  -> 反射调用真实服务方法
  -> RpcResponse 原路返回
```

整体设计原则：

- `rpc-core` 只关心 RPC 框架本身，不能依赖 Spring，否则核心能力无法在非 Spring 场景复用。
- `rpc-spring` 只负责把 Spring Bean 生命周期接到 core，不实现底层网络、协议、熔断等核心逻辑。
- `starter` 只负责自动装配和配置绑定，降低使用者接入成本。
- consumer 侧做服务级治理和客户端背压，provider 侧做线程隔离、限流、metrics 和业务执行。
- requestId 表示一次真实网络请求，traceId 表示一次业务调用链路；重试时 traceId 不变，requestId 每次 attempt 变化。

## 1. example-api：服务契约模块

### 职责

`example-api` 定义 provider 和 consumer 共享的服务接口、入参和返回对象，例如：

```text
example-api/src/main/java/com/rpc/HelloService.java
example-api/src/main/java/com/rpc/model/User.java
```

它只描述“服务能做什么”，不描述“服务如何实现”。

### 如何实现

典型结构是：

```java
public interface HelloService {
    String hello(String name);
    User getUser(Long id);
}
```

consumer 编译期只依赖接口；provider 依赖接口并提供实现类。RPC 框架在运行时把 consumer 对接口方法的调用转换成网络请求。

### 为什么这样设计

RPC 的调用模型是“面向接口编程”。如果 consumer 直接依赖 provider 实现类，就会变成本地模块依赖，失去远程调用解耦意义。把 API 独立成模块后，provider 和 consumer 只共享服务契约，部署和实现可以独立变化。

### 边界处理

- 接口方法签名变更必须兼容两端，否则 consumer 发送的方法名或参数类型 provider 找不到。
- 入参和返回值对象必须能被当前序列化器处理。
- 强烈不建议把 provider 内部实体直接作为 API 对象暴露，真实生产项目通常会单独定义 DTO。

### 面试问答

**问：为什么要单独拆 `example-api`？**

答：因为 RPC 是跨进程面向接口调用。consumer 只需要知道接口契约，不应该依赖 provider 实现。API 模块让两端共享方法签名和数据模型，同时保持 provider 实现可替换、可独立部署。

**问：如果接口升级怎么办？**

答：要考虑兼容性。新增方法通常兼容；修改参数类型、删除字段、修改返回结构可能不兼容。生产级 RPC 通常会通过版本号、服务分组、灰度发布和序列化兼容策略解决。

## 2. example-provider：服务提供方示例

### 职责

`example-provider` 展示如何把一个普通 Spring Boot 服务发布成 RPC 服务：

```text
example-provider/src/main/java/com/rpc/ExampleProviderApplication.java
example-provider/src/main/java/com/rpc/HelloServiceImpl.java
example-provider/src/main/resources/application.yml
```

核心职责：

- 启动 Spring Boot 应用。
- 提供 `HelloService` 的真实实现。
- 通过 `@RpcService` 标记服务实现类。
- 由 starter 自动扫描并注册服务。
- 启动 Netty server 并把服务地址注册到 ZooKeeper。

### 如何实现

服务实现类的典型形态：

```java
@RpcService
public class HelloServiceImpl implements HelloService {
    @Override
    public String hello(String name) {
        return "hello " + name;
    }
}
```

启动后，Spring 集成层扫描到 `@RpcService`，将实现对象注册到本地服务表。provider bootstrap 随后启动服务端 transport，并向注册中心注册当前 provider 地址。

精简后的配置只需要保留端口和注册中心地址：

```yaml
server:
  port: 18080

rpc:
  registry:
    address: ${RPC_REGISTRY_ADDRESS:127.0.0.1:2181}
```

### 为什么这样设计

provider 代码应该尽量像普通 Spring Bean 一样编写。框架通过注解和自动装配完成服务暴露，避免用户手写注册、启动 server、拼接协议参数等样板代码。

### 边界处理

- 如果没有 `@RpcService`，服务不会被注册到本地服务表，consumer 调用会出现找不到服务。
- provider 启动但 ZooKeeper 不可用时，注册会失败或延迟，consumer 无法发现该实例。
- provider 业务线程池满时不应该拖垮 Netty IO 线程，当前实现会返回 `SERVER_BUSY`。
- 心跳请求不进入业务线程池，避免业务线程池打满导致心跳无法响应。

### 面试问答

**问：provider 如何把服务暴露出去？**

答：Spring 层扫描 `@RpcService` Bean，把服务接口名和实现对象注册到 `LocalRegistry`；provider bootstrap 启动 Netty server，并把当前服务地址注册到 ZooKeeper。请求进来后，根据 serviceName 从本地注册表找到实现对象并反射调用。

**问：如果服务端压力很大怎么办？**

答：业务请求进入 `BizThreadPool`，队列满时返回 `SERVER_BUSY`，客户端可以按策略重试或失败。心跳绕开业务线程池，避免过载时连接误判。provider metrics 会记录非 200 响应，便于压测定位。

## 3. example-consumer：服务消费方示例

### 职责

`example-consumer` 展示业务方如何使用远程服务：

```text
example-consumer/src/main/java/com/rpc/ExampleConsumerApplication.java
example-consumer/src/main/resources/application.yml
```

核心职责：

- 启动 Spring Boot 应用。
- 使用 `@RpcReference` 注入远程服务接口代理。
- 像调用本地接口一样调用 provider。
- 由 starter 自动创建 consumer bootstrap、注册中心客户端、Netty client 和代理对象。

### 如何实现

典型用法：

```java
@RpcReference
private HelloService helloService;

public void run() {
    String result = helloService.hello("rpc");
}
```

这里注入的不是 provider 实现类，而是 JDK 动态代理对象。代理对象拦截接口方法调用，构造 `RpcRequest` 并交给客户端调用链。

精简后的配置：

```yaml
server:
  port: 18081

rpc:
  registry:
    address: ${RPC_REGISTRY_ADDRESS:127.0.0.1:2181}
```

### 为什么这样设计

consumer 使用成本要低。业务代码只依赖 API 模块和 starter，不需要手动创建连接池、序列化器、负载均衡器、熔断器。默认值覆盖常见场景，真正压测和上线时再按需覆盖客户端背压、超时、重试等参数。

### 边界处理

- 如果注册中心没有可用实例，服务发现会返回空，调用失败。
- 如果客户端 pending 请求超限，会快速失败为 `CLIENT_BUSY`，避免无限堆积。
- 如果下游实例熔断打开，负载均衡会跳过不可用实例或快速失败。
- 如果开启重试，每次真实网络 attempt 都会生成新的 requestId。

### 面试问答

**问：consumer 为什么能像调用本地方法一样调用远程服务？**

答：因为字段注入的是代理对象。代理对象拦截接口方法调用，把方法名、参数类型、参数值、返回类型和调用配置封装成 `RpcRequest`，然后走服务发现、负载均衡、熔断、连接池和 Netty 发送。

**问：配置为什么能这么少？**

答：框架给 transport、serializer、registry type、scan package、限流熔断等提供默认值。用户最小化只需要提供注册中心地址；压测或生产部署时再覆盖关键参数。

## 4. rpc-core/config：配置模型模块

### 职责

关键类：

```text
RpcFrameworkConfig
RpcClientConfig
RpcServerConfig
RpcConfigKeys
RpcConfigLoader
RpcClientConfigBinder
RpcServerConfigBinder
RpcFrameworkConfigBinder
RpcMethodConfigBinder
RpcFilterConfigBinder
```

配置模块负责把外部配置转换成框架运行时配置。它分三层：

- `RpcFrameworkConfig`：面向用户和框架全局的统一配置模型。
- `RpcClientConfig`：Netty client 和连接池实际需要的配置。
- `RpcServerConfig`：Netty server 和业务线程池实际需要的配置。

### 如何实现

典型转换逻辑：

```java
RpcClientConfig clientConfig = RpcClientConfig.fromFrameworkConfig(frameworkConfig);
RpcServerConfig serverConfig = RpcServerConfig.fromFrameworkConfig(frameworkConfig);
```

这样 bootstrap 不再手动拼大量 builder 字段，而是由配置对象自己完成转换。

客户端关键压测参数包括：

```text
rpc.client.max-inflight-requests-per-connection
rpc.client.max-connections-per-address
rpc.client.max-total-connections
rpc.client.max-pending-requests
rpc.client.idle-connection-ttl-millis
rpc.client.idle-connection-evict-interval-millis
rpc.client.read-timeout-millis
rpc.client.retry-times
```

服务端关键压测参数包括：

```text
rpc.server.biz-core-threads
rpc.server.biz-max-threads
rpc.server.biz-queue-capacity
rpc.server.port
```

### 为什么这样设计

如果 bootstrap 里直接读取每个配置项并拼装 Netty client/server，会导致启动类越来越臃肿。把配置转换逻辑沉到 `RpcClientConfig` 和 `RpcServerConfig` 后，bootstrap 只负责流程编排，配置模块负责参数解释。

### 边界处理

- 默认值必须合理，否则用户精简配置后项目无法启动。
- 线程数、连接数、队列长度这类参数要做下限保护，不能允许 0 或负数直接进入运行时。
- Spring Boot 配置、properties 配置和代码默认值要保持一致，避免同一个参数在不同入口语义不同。

### 面试问答

**问：为什么拆 `RpcFrameworkConfig`、`RpcClientConfig`、`RpcServerConfig`？**

答：`RpcFrameworkConfig` 是全局配置视图，面向用户；`RpcClientConfig` 和 `RpcServerConfig` 是运行时视图，分别面向客户端 transport 和服务端 runtime。这样能避免 bootstrap 过度臃肿，也避免底层 Netty 模块直接依赖 Spring Boot 配置结构。

**问：默认配置和可调参数如何平衡？**

答：默认配置保证最小接入成本；压测和生产相关参数必须暴露出来，例如 pending 上限、连接数、线程池大小、队列长度、超时和重试。默认值解决“能跑”，可调参数解决“跑得稳”。

## 5. rpc-core/api/bootstrap：启动门面模块

### 职责

关键类：

```text
RpcConsumerBootstrap
RpcProviderBootstrap
ClassPathScanner
RpcService
RpcReference
```

bootstrap 是 core 的启动门面：

- consumer bootstrap 创建注册中心客户端、服务目录、Netty client、代理工厂。
- provider bootstrap 创建本地注册表、Netty server、服务注册器。
- 注解和扫描器用于非 Spring 或底层复用场景。

### 如何实现

provider 侧启动大致流程：

```text
读取 RpcFrameworkConfig
  -> 构造 RpcServerConfig
  -> 扫描或接收服务实现
  -> 注册到 LocalRegistry
  -> 启动 RpcNettyServer
  -> 注册 provider 地址到 registry
```

consumer 侧启动大致流程：

```text
读取 RpcFrameworkConfig
  -> 构造 RpcClientConfig
  -> 创建 ServiceDirectory
  -> 创建 RpcNettyClient
  -> 创建 RpcProxyFactory
  -> 给接口生成代理对象
```

### 为什么这样设计

bootstrap 是门面，不应该承载所有细节。真正的服务发现、连接池、协议编解码、熔断限流都由对应模块实现。这样面试时可以清楚说明：bootstrap 只负责编排启动流程，不做底层执行。

### 边界处理

- close 时要释放注册中心订阅、连接池、Netty eventLoop、server 端口和业务线程池。
- provider 启动失败时不能留下半注册服务。
- consumer 关闭后连接池和 pending 请求要被清理。

### 面试问答

**问：Bootstrap 是否还能优化？**

答：已经做过一轮优化：配置拼装下沉到 `RpcClientConfig.fromFrameworkConfig` 和 `RpcServerConfig.fromFrameworkConfig`，bootstrap 只保留启动编排。后续还可以继续把 registry、transport、filter runtime 的创建抽成 factory，但当前复杂度已经适合简历项目。

## 6. rpc-core/invoke/proxy：代理模块

### 职责

关键类：

```text
RpcProxyFactory
RpcInvocationHandler
RpcMethodInterceptor
```

代理模块负责把本地接口调用转换为 RPC 请求。

### 如何实现

JDK 动态代理的核心思想：

```java
Object proxy = Proxy.newProxyInstance(
    interfaceClass.getClassLoader(),
    new Class<?>[]{interfaceClass},
    new RpcInvocationHandler(...)
);
```

调用进入 handler 后，框架组装请求：

```text
serviceName       接口名或服务名
methodName        被调用方法名
parameterTypes    参数类型数组
parameters        参数值数组
returnType        返回类型
attachments       traceId、serializer、loadBalancer、timeout 等扩展信息
```

### 为什么这样设计

RPC 的关键是让业务代码不用关心网络细节。代理层屏蔽服务发现、序列化、连接池和响应匹配，业务仍然按接口编程。

### 边界处理

- `Object` 基础方法，如 `toString`、`equals`、`hashCode`，不能错误地发起远程调用。
- 参数类型要精确传输，provider 侧反射查找方法依赖参数类型。
- 异常要按框架异常或业务异常语义返回，不能吞掉。

### 面试问答

**问：为什么不用直接注入实现类？**

答：consumer 进程里没有 provider 实现类。注入代理对象后，consumer 仍然面向接口调用，代理负责把调用转换为网络请求。

**问：JDK 代理和 CGLIB 的区别？**

答：JDK 代理基于接口，适合本项目这种 API 契约模式；CGLIB 基于子类，能代理普通类，但不能代理 `final` 类和 `final` 方法。

## 7. rpc-core/invoke/filter：过滤器链模块

### 职责

关键类：

```text
RpcFilter
DefaultFilterChain
FilterContext
FilterManager
FilterRuntimeConfig
FilterRuntimeConfigurator
TraceFilter
MdcFilter
ConsumerMetricsFilter
ConsumerCircuitBreakerFilter
ProviderMdcFilter
ProviderMetricsFilter
ProviderRateLimitFilter
```

过滤器用于把横切逻辑从主调用链里拆出来，例如 trace、MDC、metrics、限流、熔断。

### 如何实现

当前默认链路：

```text
CONSUMER: trace -> mdc -> consumerMetrics
INVOKER:  consumerCircuitBreaker
PROVIDER: providerMdc -> providerMetrics -> providerRateLimit
```

consumer 阶段先生成和传递 traceId，再记录调用级 metrics。invoker 阶段执行服务级熔断。provider 阶段先写 MDC，再用 metrics 包住 rate limit，保证限流失败也能被统计。

### 为什么这样设计

过滤器链能让主流程保持清晰。比如如果 metrics、MDC、限流都写在 `RpcClientInvocationExecutor` 或 `RpcRequestDispatcher` 里，这些类会迅速变成大杂烩。

provider 链路中 `providerMetrics -> providerRateLimit` 的顺序很关键：metrics 包住 rate limit，意味着限流拒绝也会被统计。如果反过来，限流直接短路后 metrics 看不到这类失败，线上观测会失真。

### 边界处理

- consumer metrics 不能只看异常，还要把非 200 `RpcResponse` 计为失败。
- provider metrics 也要覆盖非 200 响应，包括 `SERVER_BUSY`、限流、降级等。
- MDC 中的 requestId 只有真实网络请求前才有；consumer 早期 filter 不应假设 requestId 已存在。
- filter 顺序必须稳定，否则 trace、metrics、限流的语义会变化。

### 面试问答

**问：为什么服务级熔断和实例级熔断都要统计？**

答：二者粒度不同。服务级熔断判断某个服务整体是否可用，避免整个服务异常时继续打流量；实例级熔断判断某个 provider 节点是否可用，用于负载均衡摘除坏实例。这不是重复记账，而是两个不同维度的健康判断。

**问：为什么 provider metrics 要包住 rate limit？**

答：如果限流在 metrics 外面短路，限流失败不会进入指标，监控会误以为服务很健康。metrics 包住 rate limit 后，成功、异常、限流、繁忙都能被观测。

## 8. rpc-core/invoke/invocation：客户端调用编排模块

### 职责

关键类：

```text
RpcClientInvocationExecutor
RpcServiceResolver
RpcTransportInvoker
InvocationOptions
DefaultInvocationOptionsResolver
MethodConfig
InvocationAttachmentKeys
CircuitBreakerScope
ClusterStrategy
```

这是 consumer 侧代理和底层 transport 之间的编排中心。

### 如何实现

核心执行逻辑可以概括为：

```text
resolve InvocationOptions
  -> consumer 本地限流
  -> 写入 request attachments
  -> 执行 invoker filter 链
  -> 创建 ClusterInvoker
  -> 每次 attempt 选择服务实例
  -> 获取实例级熔断器
  -> 生成 requestId 并写入 MDC
  -> transportInvoker.invoke(request, address)
  -> 根据 response code 记录成功或失败
  -> release load balancer 计数
```

关键代码语义：

```java
rpcRequest.setRequestId(generateRequestId());
MDC.put("rpcRequestId", rpcRequest.getRequestId());
RpcResponse response = transportInvoker.invoke(rpcRequest, address);
if (response.getCode() == null || response.getCode() != 200) {
    throw RpcExceptionMapper.fromResponse(response.getCode(), response.getMessage());
}
instanceCircuitBreaker.recordSuccess();
```

### 为什么这样设计

代理层只负责把方法调用转换成请求；transport 只负责发送请求。调用编排层承接中间复杂逻辑，包括方法级配置、限流、熔断、服务发现、负载均衡、重试和 requestId 语义。

requestId 放在真实 attempt 前生成，是因为一次业务调用可能重试多次。每一次网络请求都应该有独立 requestId，方便匹配响应和排查日志；traceId 才是跨重试不变的业务链路标识。

### 边界处理

- consumer 本地限流失败时不会出网，但仍要生成本地 requestId，保证响应结构完整。
- 非 200 响应会被映射成异常，交给 cluster/retry/circuit breaker 处理。
- finally 中必须恢复 MDC，并释放 least-connections 等负载均衡计数。
- 服务发现为空、熔断打开、连接池超限都要快速失败，不能无限阻塞。

### 面试问答

**问：为什么 requestId 不在 consumer filter 最开始生成？**

答：consumer filter 阶段还只是一次本地调用意图，不一定会出网。真实网络请求发生在每个 attempt 中，重试会产生多个 attempt，所以 requestId 应在 attempt 前生成。traceId 才适合在 consumer filter 阶段生成并贯穿整个调用链路。

**问：为什么非 200 response 要抛异常？**

答：因为限流、服务繁忙、熔断、降级等都可能通过 response code 表达。如果不转成失败，重试、熔断和 metrics 都可能把失败误认为成功。

## 9. rpc-core/invoke/cluster：集群容错模块

### 职责

关键类：

```text
ClusterInvoker
ClusterInvokerFactory
FailFastClusterInvoker
FailOverClusterInvoker
```

cluster 模块决定一次调用失败后如何处理：

- `failFast`：失败立即返回，适合非幂等或不适合重试的接口。
- `failOver`：失败后按重试策略再次尝试，适合幂等读或短暂网络异常场景。

### 如何实现

调用编排器把一次真实调用包装成 `Callable<RpcResponse>`，cluster invoker 决定执行一次还是多次。

```text
FailFast:
  invoke once -> success or throw

FailOver:
  retryExecutor.execute(callable, retryTimes)
```

### 为什么这样设计

重试策略不应该写死在 transport 层。transport 只知道“把请求发到某个地址”，不知道业务是否允许重试。cluster 层按调用配置选择容错策略，职责更清晰。

### 边界处理

- 非幂等写操作不应该随意 failOver，否则可能重复写。
- 重试次数过多会放大流量，故障时可能形成重试风暴。
- 熔断打开、客户端过载、参数错误等失败不一定适合重试。

### 面试问答

**问：重试和熔断会不会冲突？**

答：不冲突。熔断用于判断是否允许继续打某个服务或实例；重试用于允许调用时应对短暂失败。重试前需要尊重熔断状态，熔断打开时应快速失败，避免重试放大故障。

## 10. rpc-core/resilience：稳定性治理模块

### 职责

关键类：

```text
CircuitBreakerImpl
CircuitBreakerManager
FixedWindowRateLimiter
RateLimiterManager
RetryExecutor
DefaultRetryStrategy
FailFastDegradation
DefaultValueDegradation
DegradationPolicyFactory
```

该模块负责限流、熔断、降级、重试。

### 如何实现

熔断器维护状态：

```text
CLOSED      正常放行并统计成功失败
OPEN        快速拒绝请求
HALF_OPEN   放少量探测请求，成功恢复，失败重新打开
```

限流器采用固定窗口思想，在单位时间窗口内控制请求数量。降级策略在失败或过载时返回 fail-fast 异常或默认值。重试策略判断异常是否可重试。

### 为什么这样设计

RPC 框架必须防止故障扩散。没有限流，服务端可能被打爆；没有熔断，客户端会持续请求坏实例；没有降级，上游可能被下游故障拖死；没有重试，短暂网络抖动会直接暴露给业务。

### 边界处理

- 服务级熔断和实例级熔断需要分开维护。
- half-open 探测不能无限并发，否则会在恢复阶段再次压垮下游。
- 限流失败应该形成明确错误码，不能伪装成成功。
- 降级结果要被观测，不应让 metrics 误判为真实成功。

### 面试问答

**问：什么时候该重试，什么时候不该重试？**

答：网络抖动、连接短暂失败、服务端繁忙这类短暂失败适合有限重试；参数错误、序列化错误、明确业务异常、非幂等写操作不适合重试。

**问：固定窗口限流有什么问题？**

答：固定窗口实现简单，但窗口边界可能出现瞬时双倍流量。生产级可以换滑动窗口、令牌桶或漏桶。本项目作为简历项目，固定窗口足够说明限流思想。

## 11. rpc-core/discovery 与 registry：注册发现模块

### 职责

关键类：

```text
ServiceRegistry
ServiceDiscovery
ServiceDirectory
ServiceDiscoveryCache
ServiceInstancesSnapshot
ZooKeeperRegistryImpl
ZkClient
ZkClientFactory
ZooKeeperClientAdapter
LocalRegistryImpl
```

provider 侧：

```text
本地注册服务实例 -> 启动 server -> 向 ZooKeeper 注册临时节点
```

consumer 侧：

```text
订阅服务节点 -> 缓存实例快照 -> watcher 更新 -> 调用时读取快照
```

### 如何实现

`ServiceDirectory` 是 consumer 侧关键缓存层：

```text
getSnapshot(serviceName)
  -> 读本地 cache
  -> cache 未过期直接返回
  -> 已订阅则 refresh
  -> 未订阅则 subscribe 并保存 listener
  -> discover 失败时按配置 fallback 到旧快照
```

它还维护地址到服务名的反向索引，用于连接失效时判断某个地址是否仍属于当前服务目录。

### 为什么这样设计

consumer 不能每次 RPC 调用都访问 ZooKeeper，否则注册中心会成为高频调用路径瓶颈。`ServiceDirectory` 用本地快照承接高频读，ZooKeeper watcher 只负责变更通知。

允许 stale fallback 是为了注册中心短暂抖动时不让业务请求瞬间全失败。只要旧实例仍可用，consumer 可以继续调用旧快照。

### 边界处理

- watcher 触发后需要重新维护订阅语义，否则后续变更可能收不到。
- ZooKeeper session expired 后需要恢复临时节点和 watcher。
- 旧快照 fallback 要有边界，不能无限记住历史地址。
- 注册中心失败不能导致调用线程无限阻塞。

### 面试问答

**问：为什么不用每次调用都查 ZooKeeper？**

答：ZooKeeper 是注册发现系统，不是业务请求链路上的高频数据库。每次调用都查会增加延迟并压垮注册中心。正确做法是本地缓存快照，watcher 异步更新。

**问：注册中心挂了还能调用吗？**

答：如果 consumer 本地有旧快照，并且 provider 实例仍可达，可以按配置 fallback 到旧快照继续调用；如果没有旧快照或旧实例不可达，则调用失败。

## 12. rpc-core/extension：SPI 与负载均衡模块

### 职责

关键类：

```text
ExtensionLoader
ExtensionFactory
SPI
Inject
Initialize
LoadBalancer
LoadBalancerFactory
RandomLoadBalancer
RoundRobinLoadBalancer
ConsistentHashLoadBalancer
LeastConnectionsLoadBalancer
SerializerFactory
```

该模块提供可插拔能力：

- 序列化器可替换。
- 负载均衡算法可替换。
- filter 可扩展。

### 如何实现

SPI 根据扩展名加载实现，并做缓存、依赖注入、初始化和失败清理。负载均衡器从服务实例列表中选择一个目标地址。

负载均衡策略：

```text
Random                随机选择，简单但不保证均匀
RoundRobin            轮询选择，使用 floorMod 处理计数器极值
ConsistentHash        一致性哈希，适合相同参数尽量打到相同节点
LeastConnections      最少连接数，倾向选择当前 inflight 更少的节点
```

### 为什么这样设计

RPC 框架不能把序列化和负载均衡写死。不同场景有不同需求：读接口可能适合 round-robin；有缓存亲和性需求时适合 consistent hash；节点处理能力差异明显时可以考虑 least-connections。

### 边界处理

- SPI 加载失败不能留下半初始化缓存。
- 循环依赖检测要基于线程内创建链路，不能用进程级全局 set 误判并发加载。
- RoundRobin 计数器溢出时不能出现负数组下标。
- LeastConnections 只有在实例通过熔断判断并真实选中后才增加计数，否则 half-open 探测失败会造成计数泄漏。

### 面试问答

**问：为什么要做 SPI？**

答：为了把框架核心流程和具体策略解耦。RPC 框架应该允许用户替换序列化器、负载均衡器和过滤器，而不是在核心代码里写死某个实现。

**问：一致性哈希适合什么场景？**

答：适合有缓存亲和性或相同请求参数希望落到同一节点的场景。节点变化时，它能减少 key 大规模迁移，但实现复杂度高于随机和轮询。

## 13. rpc-core/protocol：协议与编解码模块

### 职责

关键类：

```text
RpcHeader
RpcMessage
RpcMessageType
RpcRequest
RpcResponse
RpcHeartbeat
RpcProtocolEncoder
RpcProtocolDecoder
```

协议模块定义网络上传输的消息格式，并负责对象和字节流之间的转换。

### 如何实现

典型消息结构：

```text
RpcMessage
  header:
    magicNumber
    version
    serializerType
    messageType
    requestId
  body:
    RpcRequest / RpcResponse / RpcHeartbeat
```

编码器把 `RpcMessage` 按协议写入 `ByteBuf`；解码器按协议读取 header、选择 serializer、反序列化 body。

心跳语义：

```text
client 发送 heartbeat request，写入 timestamp
server 收到后构造 heartbeat response，原样带回 request timestamp
client 用 now - timestamp 估算 RTT
```

### 为什么这样设计

自定义协议能明确区分请求、响应和心跳，也能携带序列化类型和 requestId。requestId 是异步响应匹配的关键，serializerType 保证响应使用和请求一致的编解码方式。

### 边界处理

- magic number 和 version 用于快速识别非法包或不兼容协议。
- 解码异常时要释放 `ByteBuf`，避免直接内存泄漏。
- provider 响应必须沿用请求里的 serializerType，否则 consumer 可能无法解码。
- 心跳不能当成业务请求进入业务线程池。

### 面试问答

**问：为什么协议头里要有 serializerType？**

答：因为客户端和服务端可能支持多种序列化方式。请求头携带 serializerType 后，服务端知道如何解码请求，也能用同样类型编码响应，避免两端协议不一致。

**问：requestId 的作用是什么？**

答：客户端异步发送多个请求后，响应返回顺序不一定和发送顺序一致。requestId 用于在 pending 请求表中找到对应 future，并完成正确的调用结果。

## 14. rpc-core/transport/netty/client：客户端网络模块

### 职责

关键类：

```text
RpcNettyClient
ConnectionPool
RpcConnection
RequestManager
RpcClientHandler
HeartbeatHandler
ReconnectHandler
ClientSharedScheduler
ConnectionPoolSharedScheduler
ReconnectSharedScheduler
```

客户端网络模块负责连接管理、请求发送、pending 响应匹配、超时清理、心跳和重连。

### 如何实现

调用流程：

```text
RpcNettyClient.invoke(request, address)
  -> RequestManager 尝试占用 pending 配额
  -> ConnectionPool 获取可用连接
  -> RpcConnection 增加 inflight
  -> 写出 RpcMessage
  -> RequestManager 保存 requestId -> future
  -> RpcClientHandler 收到 response
  -> 按 requestId 完成 future
  -> finally 释放 pending 和 inflight
```

`ConnectionPool` 的核心控制点：

```text
maxInflightRequestsPerConnection   单连接并发上限
maxConnectionsPerAddress           单个 provider 地址最大连接数
maxTotalConnections                客户端总连接数上限
idleConnectionTtlMillis            空闲连接存活时间
idleConnectionEvictIntervalMillis  空闲连接清理周期
```

### 为什么这样设计

客户端虽然没有像服务端那样的业务线程池，但必须有背压能力。否则 provider 慢或网络抖动时，consumer 会无限积压 pending 请求，最终拖垮自身内存和线程。

连接池限制分三层：单连接 inflight 防止一个连接被打爆；单地址连接数控制对某个 provider 的并发；全局连接数控制整个 consumer 的资源预算。

### 边界处理

- pending 请求达到上限时返回 `CLIENT_BUSY`，不能继续堆积。
- channel inactive 时要批量失败该 channel 上未完成请求。
- 请求超时要从 pending 表清理，避免内存泄漏。
- 连接池关闭后不能继续创建连接。
- 空闲连接清理不能关闭仍有 inflight 请求的连接。

### 面试问答

**问：服务端有业务线程池，客户端为什么没有业务线程池？**

答：客户端侧主要做网络 IO 和异步响应匹配，业务线程来自调用方本身；服务端需要承载远端发来的业务方法执行，因此必须把业务执行从 Netty IO 线程隔离到业务线程池。客户端不一定需要额外业务线程池，但需要 pending、inflight、连接数这些背压控制。

**问：为什么需要 pending 请求上限？**

答：如果下游慢或不可用，客户端请求会堆在内存里。pending 上限能让系统在过载时快速失败，而不是把内存耗尽。

## 15. rpc-core/transport/netty/server：服务端网络模块

### 职责

关键类：

```text
RpcNettyServer
RpcRequestHandler
RpcRequestDispatcher
RpcRequestExecutor
BizThreadPool
ServerLifecycle
ServerHeartbeatHandler
StatisticsManager
ServiceStatistics
```

服务端网络模块负责监听端口、接收请求、区分心跳和业务请求、分发业务线程池、执行真实服务调用并返回响应。

### 如何实现

`RpcRequestHandler` 的关键逻辑：

```java
if (!isBusinessRequest(rpcMessage)) {
    processAndWrite(ctx, rpcMessage);
    return;
}

try {
    requestExecutor.execute(() -> processAndWrite(ctx, rpcMessage));
} catch (RejectedExecutionException e) {
    writeResponse(ctx, buildFailureResponse(rpcMessage, ErrorCode.SERVER_BUSY, "Server busy"));
}
```

业务请求进入 `BizThreadPool`，心跳直接处理。`RpcRequestDispatcher` 根据消息类型分发，业务请求由 `RpcRequestExecutor` 根据 serviceName 查本地服务并执行。

### 为什么这样设计

Netty IO 线程必须保持轻量，不能执行用户业务方法。业务方法可能阻塞数据库、调用下游或做 CPU 计算，如果直接在 IO 线程执行，会影响所有连接读写。业务线程池隔离后，即使业务池满了，也可以返回 `SERVER_BUSY`，而不是拖死网络层。

心跳绕过业务线程池是为了避免业务线程池满时心跳也排队超时，导致客户端误判连接不可用。

### 边界处理

- 非 RPC 消息向后传递或记录，不能强转崩溃。
- 业务线程池拒绝时返回标准失败响应。
- 写响应前检查 channel active。
- 处理异常时构造 `SERVER_ERROR` 响应，不能让客户端一直等待。
- shutdown 时要等待 active/inflight 请求 drain，但也要有超时边界。

### 面试问答

**问：为什么心跳不进业务线程池？**

答：心跳是连接健康检查，不是业务执行。如果业务线程池满时心跳也排队，客户端会把服务端业务繁忙误判成连接断开。心跳直接在 IO 流程中快速返回，可以更准确地区分网络健康和业务过载。

**问：`SERVER_BUSY` 是怎么产生的？**

答：当 provider 的业务线程池队列满或线程池拒绝任务时，`RpcRequestHandler` 捕获 `RejectedExecutionException`，构造 `SERVER_BUSY` 响应返回给客户端。

## 16. rpc-core/registry/LocalRegistry：本地服务注册模块

### 职责

关键类：

```text
LocalRegistry
LocalRegistryImpl
RpcRequestExecutor
```

provider 侧本地注册表负责保存 serviceName 到服务实现对象的映射。

### 如何实现

服务发布时：

```text
@RpcService Bean
  -> 提取接口名 / 服务名
  -> LocalRegistry.register(serviceName, bean)
```

请求执行时：

```text
RpcRequest.serviceName
  -> LocalRegistry.getService(serviceName)
  -> 反射查找 methodName + parameterTypes
  -> invoke(parameters)
```

### 为什么这样设计

ZooKeeper 只保存服务地址，不保存 Java 对象。真正执行服务方法必须在 provider 进程内完成，因此需要本地注册表把服务名映射到实现对象。

### 边界处理

- 服务不存在时返回明确错误，不能 NPE。
- 方法不存在时返回明确错误。
- 反射调用异常要解包并转成 RPC 响应。
- 同名服务重复注册要有覆盖或拒绝策略，避免不可预期行为。

### 面试问答

**问：注册中心里保存的是服务对象吗？**

答：不是。注册中心保存 provider 地址和元数据；provider 进程内的 `LocalRegistry` 保存服务名到实现对象的映射。consumer 先从注册中心找到地址，再由 provider 本地执行具体方法。

## 17. rpc-core/observability：可观测模块

### 职责

关键类：

```text
ServiceMetrics
ServiceMetricsManager
ClientRuntimeMetrics
ClientRuntimeMetricsManager
ServerRuntimeMetrics
ObservabilitySnapshot
RpcObservabilityEndpoint
RpcObservabilityFacade
RpcObservabilityResponse
```

该模块负责把运行时状态暴露出来，便于压测和排障。

### 如何实现

metrics 记录维度包括：

```text
调用成功 / 失败
非 200 响应
调用耗时
pending 请求
连接数限制拒绝
线程池状态
服务端 active/inflight
```

starter 通过 observability endpoint 将快照暴露给 Spring Boot 应用。

### 为什么这样设计

没有观测能力就无法调优。比如 timeout 增多时，需要判断是客户端 pending 满、服务端线程池满、连接数不足、注册中心抖动还是下游业务慢。

### 边界处理

- metrics 要覆盖异常和非 200 响应。
- 指标采集不能成为主调用链瓶颈。
- endpoint 暴露的数据要避免包含敏感业务参数。

### 面试问答

**问：如何用指标定位压测瓶颈？**

答：如果 `CLIENT_BUSY` 多，看客户端 pending、连接数和单连接 inflight；如果 `SERVER_BUSY` 多，看 provider 业务线程池和队列；如果 timeout 多，看服务端排队、网络、GC 和 readTimeout；如果熔断频繁打开，看实例失败率和阈值。

## 18. rpc-spring：Spring 集成模块

### 职责

关键类：

```text
EnableRpc
RpcSpringRegistrar
RpcSpringManager
```

该模块负责把 core 的 bootstrap 接入 Spring 容器生命周期。

### 如何实现

核心流程：

```text
Spring 启动
  -> 注册 RpcSpringManager
  -> 扫描 @RpcService
  -> 注册 provider 服务
  -> 扫描 @RpcReference
  -> 创建 consumer 代理并注入字段
  -> 应用关闭时释放 bootstrap 和 transport
```

### 为什么这样设计

RPC 框架要让 Spring 用户少写接入代码，但不应该把 Spring 逻辑写进 core。`rpc-spring` 作为适配层，把 Spring Bean 生命周期转成 core bootstrap 调用。

### 边界处理

- 字段注入失败要明确报错。
- 服务扫描包要能从配置或 Spring Boot 默认包推断。
- Spring 容器关闭时要释放连接池、注册中心订阅和 server 资源。

### 面试问答

**问：Spring 集成层和 core 的边界是什么？**

答：Spring 层只做 Bean 扫描、代理注入、生命周期对接和配置适配；协议、网络、注册发现、熔断限流都在 core。这样 core 可复用，Spring 只是一个接入方式。

## 19. rpc-spring-boot-starter：自动装配模块

### 职责

关键类：

```text
RpcSpringBootAutoConfiguration
RpcBootFrameworkProperties
RpcSpringBootProperties
RpcObservabilityEndpoint
RpcObservabilityFacade
RpcObservabilityResponse
```

starter 负责自动创建框架 Bean、绑定 `application.yml`、推断默认扫描包并暴露可观测入口。

### 如何实现

配置绑定路径：

```text
application.yml
  -> RpcBootFrameworkProperties
  -> RpcFrameworkConfig
  -> RpcClientConfig / RpcServerConfig
```

扫描包推断顺序：

```text
rpc.spring.scan-packages
  -> rpc.server.scan-packages
  -> Spring Boot AutoConfigurationPackages
```

### 为什么这样设计

用户接入 RPC 框架时，最理想状态是加依赖、写注解、配注册中心地址即可。默认扫描包从 Spring Boot 应用主包推断，可以减少重复配置。

### 边界处理

- 默认值要能覆盖 example 的最小配置。
- 手动配置 scan package 时必须优先生效。
- starter 不能吞掉底层启动异常，否则用户不知道 RPC 没有启动。
- observability endpoint 应只读，不应修改运行时状态。

### 面试问答

**问：为什么 starter 能让 `application.yml` 变少？**

答：因为 transport、serializer、registry type、scan package、线程池和客户端背压都有默认值。用户只需要写实际环境变化的值，比如注册中心地址。压测或上线再按指标覆盖关键参数。

## 20. 一次完整调用的模块协作

以 `helloService.hello("rpc")` 为例：

```text
1. example-consumer 中调用接口方法。
2. @RpcReference 注入的代理对象拦截调用。
3. 代理层构造 RpcRequest，写入 serviceName、methodName、parameterTypes、parameters。
4. consumer filter 写 traceId、MDC、metrics。
5. RpcClientInvocationExecutor 解析方法级配置。
6. consumer 本地限流通过后进入 invoker filter。
7. 服务级熔断判断是否允许调用。
8. ServiceDirectory 获取 provider 实例快照。
9. LoadBalancer 选择一个可用实例，并结合实例级熔断过滤坏节点。
10. 每次真实 attempt 前生成 requestId 并写 MDC。
11. RpcNettyClient 从 ConnectionPool 取连接。
12. RequestManager 注册 pending future。
13. RpcProtocolEncoder 序列化并写出请求。
14. provider 解码后进入 RpcRequestHandler。
15. 业务请求进入 BizThreadPool；心跳直接处理。
16. RpcRequestDispatcher 分发到 RpcRequestExecutor。
17. provider filter 写 MDC、metrics，并执行 provider 限流。
18. LocalRegistry 找到服务实现并反射调用。
19. RpcResponse 沿原连接返回。
20. consumer RpcClientHandler 按 requestId 完成 future。
21. 调用编排器记录实例级熔断成功或失败，释放负载均衡计数。
22. consumer metrics 记录最终结果并返回业务代码。
```

## 21. 当前项目最容易被细问的点

### requestId 和 traceId

回答要点：traceId 表示一次业务调用链路；requestId 表示一次真实网络请求。重试时 traceId 保持不变，requestId 每次 attempt 重新生成。

### 服务级熔断和实例级熔断

回答要点：服务级判断服务整体健康，实例级判断某个 provider 节点健康。二者统计维度不同，不能混为一谈。

### 服务端线程池和客户端背压

回答要点：服务端用业务线程池承载远程业务执行；客户端不额外承载 provider 业务，但通过 pending、inflight、连接数限制做背压。

### metrics 统计口径

回答要点：只有 code 200 才算成功；异常和非 200 响应都算失败。provider metrics 包住 rate limit，避免限流失败丢指标。

### ZooKeeper 缓存和 fallback

回答要点：调用时读本地服务目录快照，不每次查 ZK；注册中心短暂失败时可回退旧快照；session expired 后需要恢复临时节点和 watcher。

### 连接池三层限制

回答要点：单连接 inflight 控制连接内并发；单地址连接数控制对某 provider 的压力；全局连接数控制客户端总资源预算。

### 心跳绕过业务线程池

回答要点：心跳代表连接健康，不代表业务处理能力。业务池满时仍应能返回心跳，避免误判连接断开。

## 22. 生产级边界

当前项目适合描述为“自研 RPC 框架核心链路实现与稳定性治理实践”，不要描述为可直接替代 Dubbo 的生产级框架。

可主动说明的后续演进：

- 支持异步 Future API、泛化调用、流式调用。
- 增加服务版本、分组、权重、标签路由和灰度发布。
- 接入动态配置中心，让限流、熔断、超时、重试参数运行时可变。
- 接入 OpenTelemetry、Prometheus、Grafana 和统一 trace。
- 增加鉴权、TLS、序列化白名单和安全治理。
- 做长时间 soak test、故障注入和大规模连接压测。

推荐面试收束语：

```text
这个项目的重点不是声称替代成熟 RPC 框架，而是把 RPC 核心链路从代理、协议、序列化、注册发现、负载均衡、Netty 网络，到限流、熔断、降级、重试、线程隔离、metrics 和 Spring Boot Starter 做成一个完整闭环。对于生产级能力，我能明确说出当前边界和后续演进方向。
```
