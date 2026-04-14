# RPC 项目面试作战文档

## 1. 简历怎么写

推荐项目名：

```text
自研 Java RPC 框架
```

如果想更工程化：

```text
基于 Netty + ZooKeeper + Spring Boot Starter 的自研 RPC 框架
```

不要写得太泛：

```text
RPC 项目
```

## 2. 简历项目描述

稳健版：

```text
基于 Java 实现自研 RPC 框架，打通 consumer 接口代理调用、请求对象化、服务注册发现、Netty 请求响应通信和 provider 本地执行的完整远程调用闭环；支持 Spring / Spring Boot 接入、SPI 扩展、可插拔序列化与负载均衡，并补充方法级配置、限流、熔断、降级、重试、连接池、请求超时清理和可观测指标等治理能力。
```

分点版：

```text
- 基于 Java 自研 RPC 框架，完成 consumer 代理调用到 provider 本地服务执行的完整调用闭环。
- 设计 RpcRequest/RpcResponse 协议模型和 Netty 编解码链路，支持请求响应匹配、长连接复用、心跳和断链清理。
- 实现基于 ZooKeeper 的服务注册发现，支持服务目录缓存、watcher 订阅、session 过期恢复和注册中心失败时 stale fallback。
- 引入 SPI 扩展机制，支持序列化器、负载均衡器和过滤器扩展，并修复并发加载和失败恢复问题。
- 补充客户端连接池、pending 请求上限、服务端业务线程池、限流、熔断、降级、重试和可观测指标，提升框架在高并发和异常场景下的稳定性。
- 封装 Spring Boot Starter，支持少量 application.yml 配置和注解方式接入 provider / consumer。
```

更强调优化的版本：

```text
围绕自研 RPC 框架做稳定性优化：为客户端补齐连接池、in-flight 请求控制、pending 请求硬上限、超时扫描、断链批量失败和共享调度器；为服务端补齐业务线程池隔离、过载返回 SERVER_BUSY、心跳快速路径和优雅停机；并修正熔断统计、MDC/requestId 语义、metrics 失败判断、负载均衡状态泄漏和 ZooKeeper session 恢复链路。
```

## 3. 30 秒项目介绍

```text
这是一个自研 Java RPC 框架，目标是让 consumer 像调用本地接口一样调用 provider 远程服务。项目核心链路是：consumer 通过代理把方法调用转成 RpcRequest，经服务发现、负载均衡、熔断、重试和 Netty 传输发到 provider；provider 收到请求后从本地注册表找到服务实现，执行后返回 RpcResponse。项目还封装了 Spring Boot Starter，并补充了连接池、限流、降级、超时清理和可观测指标等稳定性能力。
```

## 4. 2 分钟项目介绍

```text
这个项目是一个自研 Java RPC 框架，目标是屏蔽远程调用细节，让 consumer 侧通过接口代理访问 provider 的远程服务。consumer 侧启动时，Spring Boot Starter 会读取 rpc 配置并扫描 @RpcReference 字段，框架为接口生成代理对象；业务代码调用接口方法时，代理会构造 RpcRequest，然后进入 consumer filter、调用编排器、服务发现、负载均衡、熔断、重试和 Netty transport。

provider 侧启动时，框架会扫描 @RpcService Bean，把服务实现注册到本地注册表，同时把服务地址注册到 ZooKeeper。provider 收到请求后，Netty handler 会先区分心跳和业务请求，心跳走快速路径，业务请求进入业务线程池。业务线程里恢复 RpcContext，执行 provider 侧 MDC、metrics、限流 filter，再从本地注册表找到服务对象并反射调用，最后返回 RpcResponse。

我后续重点做了稳定性优化，比如客户端连接池、单连接 in-flight 上限、pending 请求硬上限、超时扫描、断链批量失败、服务端业务线程池隔离、SERVER_BUSY、ZooKeeper session 恢复、熔断统计语义修正、非 200 响应计入失败指标，以及 Spring Boot 配置精简和可观测 endpoint。
```

## 5. 5 分钟展开结构

按这个顺序讲：

1. 项目目标：让 consumer 像本地调用一样调 provider。
2. 模块拆分：api、provider、consumer、core、spring、starter。
3. consumer 链路：代理、request、filter、invocation executor。
4. 服务发现：ZooKeeper、ServiceDirectory、缓存、watcher。
5. 选址和治理：负载均衡、服务级熔断、实例级熔断、retry。
6. transport：Netty、协议、连接池、pending request。
7. provider 链路：handler、dispatcher、biz executor、filter、本地注册表。
8. 稳定性优化：背压、超时、断链、心跳、metrics、MDC。
9. Spring Boot 接入：自动装配、配置默认值、scan package 推断。
10. 项目边界：不是商用级，但主链路完整、可解释、可扩展。

## 6. 高频项目问题和回答

### 6.1 这个项目解决什么问题？

```text
它解决的是 Java 进程之间远程服务调用的问题。consumer 不需要知道 provider 的具体地址和网络细节，只依赖服务接口；框架负责把本地方法调用转成网络请求，经过服务发现和传输到 provider，执行后再把结果返回给 consumer。
```

### 6.2 为什么要单独拆 example-api？

```text
RPC 调用是面向接口的，consumer 和 provider 必须共享服务契约。api 单独拆出来后，consumer 只依赖接口，不依赖 provider 实现；provider 负责实现接口并发布服务。这符合面向接口编程，也避免 consumer 和 provider 编译期耦合。
```

### 6.3 Consumer 端怎么把本地调用变成远程调用？

```text
通过代理。@RpcReference 注入的不是 provider 实现，而是框架生成的代理对象。业务代码调用接口方法后，RpcInvocationHandler 会接管调用，把方法名、参数类型、参数值、返回类型和服务接口名封装成 RpcRequest，然后交给后面的调用编排和网络传输链路。
```

### 6.4 Provider 端怎么找到具体实现？

```text
provider 启动时会扫描 @RpcService，把服务接口名和实现 Bean 注册到 LocalRegistry。请求到达 provider 后，RpcRequestExecutor 根据 request.serviceName 从 LocalRegistry 拿到服务对象，再根据 methodName 和 parameterTypes 反射执行目标方法。
```

### 6.5 为什么需要注册中心？

```text
因为 consumer 不应该写死 provider 地址。provider 启动后把自己的服务名和地址注册到 ZooKeeper，consumer 调用时通过服务发现获取可用地址列表。这样 provider 扩缩容、重启、下线时，consumer 可以动态感知。
```

### 6.6 为什么还需要本地注册表？

```text
注册中心解决的是 consumer 如何发现 provider 地址；本地注册表解决的是 provider 收到请求后如何找到本 JVM 内的服务实现对象。这两个注册表解决的问题不同，一个是跨进程地址发现，一个是进程内服务定位。
```

### 6.7 为什么要有 RpcClientInvocationExecutor？

```text
代理层只适合做入口拦截和 RpcRequest 构造。一次真实 RPC 调用还涉及方法级配置、限流、服务级熔断、服务发现、负载均衡、实例级熔断、cluster 策略、retry 和 requestId 生成。如果都塞进代理类，代理会非常重。所以抽出 RpcClientInvocationExecutor 作为 consumer 调用编排中心。
```

### 6.8 为什么 requestId 不在 consumer filter 里生成？

```text
因为 requestId 在当前项目里表示一次真实网络请求 attempt，而不是一次业务调用。failover 重试时，同一个业务调用可能发起多次网络请求，每次 attempt 都应该有不同 requestId，方便 pending request 匹配和日志排查。traceId 才是业务调用级别，会在 consumer 入口生成并透传到 provider。
```

### 6.9 为什么心跳不进入服务端业务线程池？

```text
心跳是连接健康探测，不是业务请求。如果业务线程池打满，心跳也被拒绝，客户端会把业务过载误判成连接异常。现在心跳在 Netty handler 层走快速路径，业务请求才进入 biz executor。
```

### 6.10 为什么 provider metrics 要包住 rateLimit？

```text
因为限流本身也是一次请求结果。如果 rateLimit 在 metrics 外层，限流短路时 metrics 根本记录不到这次请求，监控会低估失败率和流量。现在 providerMdc -> providerMetrics -> providerRateLimit，限流/降级响应也会进入指标。
```

### 6.11 服务级熔断和实例级熔断为什么都要统计？

```text
两者统计维度不同。服务级熔断看的是 consumer 对某个服务整体调用是否健康，适合在入口快速短路；实例级熔断看的是某个 provider 实例是否健康，适合在选址时避开坏实例。一次真实发到某个实例的失败，同时影响服务整体体验和该实例健康，因此两边都统计是合理的。但如果还没选中实例就失败，比如服务发现失败，就只影响服务级，不应该影响实例级。
```

### 6.12 为什么 pending request 还要按 channel 建反向索引？

```text
因为某个 channel 断开时，只应该失败这个 channel 上的请求，而不是影响其他连接上的请求。channel -> requestIds 的反向索引可以在 channelInactive 或 exceptionCaught 时批量失败对应 pending request。
```

### 6.13 为什么连接池要有三层限制？

```text
单连接 in-flight 限制防止一个 channel 上堆太多请求；单地址连接数限制防止一个 provider 地址创建过多连接；全局连接数限制防止多服务、多实例场景下整体连接数量失控。三者分别保护不同维度的资源。
```

### 6.14 为什么线程池拒绝时返回 SERVER_BUSY？

```text
如果直接关闭连接，consumer 只能看到网络异常，很难区分是服务过载还是网络问题。返回 SERVER_BUSY 能明确告诉调用方 provider 当前过载，后续可以结合重试、熔断或降级处理。
```

### 6.15 ZooKeeper session expired 后为什么要恢复节点和 watcher？

```text
ZooKeeper 的临时节点和 session 绑定，session expired 后 provider 之前注册的临时节点会失效，consumer 之前挂的 watcher 也需要重新建立。如果不恢复，服务注册和订阅都会进入不一致状态。
```

### 6.16 SPI 加载为什么要处理并发和失败恢复？

```text
SPI 是框架扩展点，可能在并发场景下被多个线程加载。如果循环依赖检测用进程级全局 set，可能把不同线程加载同一个实现误判为循环依赖。资源加载失败时也不能留下 initialized=true 的半初始化状态，否则后续无法重试。
```

## 7. 如果被问缺点

可以诚实回答：

```text
它还不是生产级 RPC 框架，主要不足有：还没有完整的异步 Future API 和泛化调用；服务治理能力还比较基础，比如路由、权重、灰度、标签路由不完整；协议兼容性、版本协商和安全认证还比较弱；可观测性目前是框架内 snapshot 和 HTTP endpoint，没有完整接入 Prometheus / tracing 体系。但作为自研 RPC 项目，它已经完整覆盖了理解 RPC 框架最核心的代理、注册发现、协议传输、provider 执行、容错和可观测链路。
```

这个答法有两个好处：

- 不盲目吹生产级。
- 能说明你知道生产级框架还缺什么。
