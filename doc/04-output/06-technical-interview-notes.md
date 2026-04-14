# RPC 项目技术八股与发散追问手册

这份文档不是普通八股清单，而是围绕当前 RPC 项目建立“项目点 -> 可追问技术栈 -> 回答边界 -> 可继续发散”的面试手册。面试时不要孤立背概念，要先落回项目，再展开底层原理。

推荐回答结构：

```text
1. 先说项目里哪里用到了这个点。
2. 再说为什么这样设计。
3. 再说底层原理。
4. 最后说边界、风险和后续优化。
```

## 0. 项目技术发散地图

当前项目可被追问的技术面很广，主要来自这些项目点：

```text
@RpcReference 动态代理
  -> JDK 动态代理、反射、CGLIB、AOP、接口契约、泛型擦除

provider 反射调用
  -> Method 查找、参数类型匹配、异常解包、Method 缓存、MethodHandle

Netty 通信
  -> Reactor、EventLoop、ChannelPipeline、ByteBuf、粘包拆包、TCP、心跳、重连、零拷贝

自定义协议
  -> magic/version/header/body、requestId、serializerType、协议兼容、大小端、半包

序列化
  -> JSON、Kryo、Hessian、Protobuf、Java 原生序列化、兼容性、安全性、性能

ZooKeeper 注册发现
  -> 临时节点、watcher、session、CAP、ZAB、选主、脑裂、注册中心高可用

ServiceDirectory 本地缓存
  -> 缓存一致性、TTL、stale fallback、single flight、缓存穿透类比、是否需要 Redis

负载均衡
  -> 随机、轮询、一致性哈希、最少连接、权重、预热、平滑加权、节点摘除

熔断限流降级重试
  -> Hystrix/Sentinel 思想、状态机、滑动窗口、令牌桶、漏桶、重试风暴、幂等

客户端背压
  -> pending 上限、inflight 上限、连接池、资源预算、快速失败、超时清理

服务端线程池
  -> ThreadPoolExecutor、拒绝策略、队列、线程隔离、IO 线程与业务线程隔离

JUC 数据结构
  -> ConcurrentHashMap、AtomicInteger、AtomicBoolean、CAS、volatile、锁、内存可见性

MDC / traceId / requestId
  -> ThreadLocal、日志链路、异步上下文传播、一次业务调用与一次网络请求

SPI 扩展机制
  -> Java SPI、Dubbo SPI、扩展点、依赖注入、缓存、循环依赖、类加载器

Spring Boot Starter
  -> 自动装配、ConfigurationProperties、BeanPostProcessor、生命周期、条件装配

可观测性
  -> metrics、错误码、日志、trace、Prometheus、OpenTelemetry、压测指标
```

边界提醒：

- 项目当前没有使用 Redis。被问到 Redis 时，不要硬说项目用了 Redis；应该说明项目里对应的是 `ServiceDirectory` 本地缓存和 ZooKeeper 注册发现，然后发散到如果引入 Redis 可以用在哪些场景。
- 项目当前没有完整动态配置中心。被问到运行时调参时，应说明当前支持配置化启动，后续可接 Nacos/Apollo/ZK 节点监听做动态刷新。
- 项目当前不是生产级 Dubbo 替代品。回答重点是核心链路闭环和稳定性治理，而不是夸大生产能力。

## 1. 动态代理、AOP 与接口契约

### 项目中对应的位置

相关代码：

```text
rpc-core/src/main/java/com/rpc/core/invoke/proxy/RpcProxyFactory.java
rpc-core/src/main/java/com/rpc/core/invoke/proxy/impl/RpcInvocationHandler.java
rpc-core/src/main/java/com/rpc/core/invoke/proxy/impl/RpcMethodInterceptor.java
rpc-core/src/main/java/com/rpc/core/api/annotation/RpcReference.java
```

consumer 通过 `@RpcReference` 注入的是代理对象，不是 provider 实现类。代理对象拦截接口方法调用，构造 `RpcRequest`，再交给 consumer 调用链。

### 必须会答

**问：JDK 动态代理的原理是什么？**

答：JDK 动态代理基于接口在运行时生成代理类，代理类实现目标接口，方法调用会被转发到 `InvocationHandler.invoke`。本项目服务调用天然以接口为契约，所以适合用 JDK 动态代理。

**问：为什么 RPC 适合用动态代理？**

答：consumer 本地没有 provider 实现类，只知道服务接口。动态代理可以让业务代码像调用本地接口一样调用远程服务，把方法名、参数类型、参数值等调用语义转换成网络请求。

**问：JDK 动态代理和 CGLIB 区别？**

答：JDK 代理要求有接口；CGLIB 通过生成子类代理普通类，但不能代理 `final` 类和 `final` 方法。RPC 推荐接口契约，所以 JDK 代理更贴合。

### 可继续发散

**问：Spring AOP 和这里的代理有什么关系？**

答：二者都可以通过代理实现方法拦截。Spring AOP 主要用于容器内横切逻辑，如事务、日志；本项目代理用于把接口调用转换为 RPC 网络调用。目的不同，但技术思想类似。

**问：代理层要不要处理 `equals`、`hashCode`、`toString`？**

答：要考虑。这些是 `Object` 方法，不应该被当成远程服务方法发送出去。否则打印代理对象或比较对象时可能误发 RPC 请求。

**问：泛型接口会不会有问题？**

答：Java 泛型有类型擦除，运行时拿到的方法参数类型可能不是业务想象中的完整泛型信息。当前项目主要依赖 `methodName + parameterTypes` 定位方法，复杂泛型序列化兼容需要额外约束。

**问：如果接口有默认方法怎么办？**

答：默认方法可以在接口中提供实现，但 RPC 代理通常会统一拦截接口方法。是否本地执行默认方法，需要框架明确语义。当前项目可以作为后续增强点，不建议在面试中夸大。

## 2. 反射、方法查找与调用性能

### 项目中对应的位置

相关模块：

```text
rpc-core/src/main/java/com/rpc/core/registry/LocalRegistry.java
rpc-core/src/main/java/com/rpc/core/registry/local/LocalRegistryImpl.java
rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestExecutor.java
```

provider 收到请求后，根据 `serviceName` 找到本地实现对象，再根据 `methodName + parameterTypes` 找到目标方法并反射调用。

### 必须会答

**问：为什么 provider 需要反射？**

答：provider 在运行时才知道请求要调用哪个服务和方法。反射允许框架根据请求里的方法名和参数类型动态定位方法并执行。

**问：反射性能差怎么办？**

答：RPC 场景主要瓶颈通常在网络、序列化、线程池排队和业务逻辑，但反射也可以优化。常见优化是缓存 `Method`，避免每次 `getMethod`；进一步可以用 `MethodHandle` 或生成字节码。

**问：反射调用异常怎么处理？**

答：反射调用可能抛 `InvocationTargetException`，需要解包拿到真实业务异常，再转换为统一 `RpcResponse`。否则客户端只能看到反射包装异常，不利于排查。

### 可继续发散

**问：`getMethod` 和 `getDeclaredMethod` 区别？**

答：`getMethod` 查 public 方法，包括父类和接口；`getDeclaredMethod` 查当前类声明的方法，包括 private，但不查父类。RPC 服务接口通常是 public 方法，用 `getMethod` 更贴合。

**问：参数类型为什么要随请求传输？**

答：Java 支持方法重载，只有方法名不够定位方法。需要 `methodName + parameterTypes` 才能准确找到目标方法。

**问：反射会破坏封装吗？**

答：反射可以访问正常代码不应访问的成员，但本项目只调用服务接口暴露的方法，不依赖反射强行访问 private 成员，因此边界可控。

## 3. Java 内存模型、volatile、CAS 与原子类

### 项目中对应的位置

项目中大量运行时状态需要并发访问：

```text
ConnectionPool.totalConnectionCount
ConnectionPool.closed
RequestManager.pendingCount
RpcConnection.inflightRequestCount
CircuitBreakerImpl 状态和计数
LeastConnectionsLoadBalancer active count
```

这些场景使用 `AtomicInteger`、`AtomicBoolean` 或并发容器来保证并发安全。

### 必须会答

**问：CAS 是什么？**

答：CAS 是 Compare-And-Swap，比较内存中的旧值是否等于预期值，如果相等就更新。它是很多原子类的基础，适合轻量级并发计数或状态切换。

**问：CAS 有什么问题？**

答：常见问题包括 ABA、长时间自旋消耗 CPU、只能保证单变量原子更新。ABA 可用版本号或 `AtomicStampedReference` 解决，但本项目里的计数场景通常不需要处理 ABA。

**问：volatile 能保证原子性吗？**

答：不能。`volatile` 保证可见性和禁止部分指令重排，但 `count++` 仍然是读、改、写三个步骤，不具备原子性。计数要用原子类或锁。

### 可继续发散

**问：为什么连接池总连接数用 AtomicInteger？**

答：连接创建可能由多个调用线程并发触发。总连接数是全局预算，需要通过 CAS 先占位，避免多个线程同时认为还有名额而超出上限。

**问：AtomicInteger 和 LongAdder 怎么选？**

答：`AtomicInteger` 适合需要读取准确值并做上限判断的场景；`LongAdder` 适合高并发统计累加，最终一致即可。本项目连接数、pending 上限需要精确判断，所以用 AtomicInteger 更合适。

**问：JMM 里 happens-before 和项目有什么关系？**

答：线程池提交任务、volatile/atomic 更新、ConcurrentHashMap 操作都依赖 JMM 的可见性规则。比如 pending 请求注册后，Netty handler 在另一个线程收到响应，需要能看到 pending 表里的 future。

## 4. ConcurrentHashMap 与并发容器

### 项目中对应的位置

项目使用并发 Map 保存高频并发状态：

```text
RequestManager: requestId -> pending future
ConnectionPool: address -> connection group
ServiceDirectory: serviceName -> listener / refresh future
ServiceMetricsManager: serviceName -> metrics
ExtensionLoader: extensionName -> instance
```

### 必须会答

**问：为什么不用 HashMap？**

答：这些结构会被多个线程同时读写。普通 `HashMap` 线程不安全，可能出现数据覆盖、不可见甚至结构异常。`ConcurrentHashMap` 能提供并发读写能力。

**问：ConcurrentHashMap 是不是所有操作都线程安全？**

答：单次 put/get/remove 是线程安全的，但复合逻辑仍要注意原子性。比如先判断再插入要用 `putIfAbsent` 或 `computeIfAbsent`，不能用 `containsKey + put`。

**问：ConcurrentHashMap 为什么不允许 null？**

答：因为并发场景下 null 会造成歧义：无法区分 key 不存在，还是 value 本身就是 null。

### 可继续发散

**问：Java 7 和 Java 8 的 ConcurrentHashMap 有什么区别？**

答：Java 7 主要是 Segment 分段锁；Java 8 改成数组 + 链表/红黑树 + CAS + synchronized 锁桶头，粒度更细。

**问：computeIfAbsent 有什么风险？**

答：mapping function 不应该做耗时或可能递归修改同一个 map 的操作，否则会影响该桶上的并发。项目中可以用它创建轻量状态对象，例如连接组或 metrics。

**问：CopyOnWriteArrayList 为什么适合连接组快照？**

答：连接组读多写少，读取时不加锁，写入时复制数组。它适合连接变更不频繁、选择连接很频繁的场景；如果连接频繁增删，则写放大会变成问题。

## 5. ThreadLocal、MDC、traceId 与 requestId

### 项目中对应的位置

相关代码：

```text
TraceFilter
MdcFilter
ProviderMdcFilter
RpcClientInvocationExecutor
```

项目区分两类 ID：

```text
traceId    一次业务调用链路，重试时不变
requestId  一次真实网络请求，重试时每次 attempt 重新生成
```

### 必须会答

**问：MDC 是什么？**

答：MDC 是日志框架提供的上下文能力，通常基于 ThreadLocal，把 traceId、requestId 等信息绑定到当前线程，日志打印时自动带上。

**问：为什么 requestId 不在 consumer filter 最开始生成？**

答：consumer filter 阶段只是一次本地调用意图，后面可能被限流、熔断短路，也可能因为重试产生多个网络 attempt。requestId 应该对应一次真实网络请求，所以在 `RpcClientInvocationExecutor` 的 attempt 前生成。

**问：ThreadLocal 有什么风险？**

答：线程池复用线程时，如果不清理 ThreadLocal，后续任务可能读到上一次请求的数据，甚至导致内存泄漏。因此 MDC 设置后要在 finally 中恢复或清理。

### 可继续发散

**问：异步线程中 MDC 会丢吗？**

答：会。MDC 默认绑定当前线程，线程切换后不会自动传播。需要手动包装任务、使用 TTL 类库，或在执行点重新设置上下文。本项目 provider 侧在处理请求时重新写 MDC，避免依赖调用线程传播。

**问：traceId 和 requestId 为什么不能合并？**

答：一次业务调用可能有多个网络请求，例如重试、failover。traceId 用于串联整条业务链路，requestId 用于匹配某次请求响应。合并后无法区分重试 attempt，也不利于 pending 表匹配。

**问：日志链路和分布式追踪有什么区别？**

答：MDC 只是日志上下文；分布式追踪还包括 span、parent span、时间线、采样、跨进程传播和可视化。生产级可以接 OpenTelemetry。

## 6. ThreadPoolExecutor 与服务端线程隔离

### 项目中对应的位置

相关代码：

```text
rpc-core/src/main/java/com/rpc/core/runtime/server/BizThreadPool.java
rpc-core/src/main/java/com/rpc/core/transport/netty/server/handler/RpcRequestHandler.java
```

provider 侧用业务线程池承载远端方法执行，Netty IO 线程只负责网络读写和轻量分发。

### 必须会答

**问：为什么服务端要有业务线程池？**

答：Netty IO 线程不能执行阻塞业务。provider 方法可能访问数据库、调用下游或做 CPU 计算，如果直接在 IO 线程执行，会影响整个 server 的网络读写能力。业务线程池把 IO 和业务执行隔离开。

**问：ThreadPoolExecutor 的核心参数有哪些？**

答：核心线程数、最大线程数、空闲线程存活时间、任务队列、线程工厂、拒绝策略。本项目最关键的是核心线程、最大线程和队列容量。

**问：队列满了怎么办？**

答：当前项目捕获 `RejectedExecutionException`，返回 `SERVER_BUSY`。这样调用方可以快速失败或按重试策略处理，而不是一直阻塞。

### 可继续发散

**问：为什么不使用无界队列？**

答：无界队列会让请求无限堆积，延迟越来越高，最终可能 OOM。RPC 框架更应该有明确资源边界，过载时快速失败。

**问：CPU 密集型和 IO 密集型线程数怎么配？**

答：CPU 密集型通常接近 CPU 核数或略多；IO 密集型由于等待时间多，线程数可以更高。RPC provider 业务通常混合，需要通过压测看 CPU、队列等待、响应时间和拒绝率来调。

**问：拒绝策略有哪些？**

答：JDK 内置有 AbortPolicy、CallerRunsPolicy、DiscardPolicy、DiscardOldestPolicy。本项目语义更接近 AbortPolicy，然后框架捕获异常返回 `SERVER_BUSY`。

**问：客户端为什么没有业务线程池？**

答：客户端的业务线程来自调用方本身，Netty 负责异步 IO 和响应回调。客户端重点不是承载 provider 业务，而是通过 pending、inflight 和连接数做背压。

## 7. Netty Reactor、EventLoop 与 Pipeline

### 项目中对应的位置

相关代码：

```text
RpcNettyClient
RpcNettyServer
RpcClientHandler
RpcRequestHandler
RpcProtocolEncoder
RpcProtocolDecoder
HeartbeatHandler
ReconnectHandler
ServerHeartbeatHandler
```

Netty 负责 TCP 连接、异步 IO、pipeline 编解码、心跳和重连。

### 必须会答

**问：Netty 为什么适合 RPC？**

答：RPC 需要高并发网络通信、异步读写、自定义协议、连接复用和 pipeline 扩展。Netty 提供成熟的事件驱动模型和 ByteBuf 管理，适合实现高性能 RPC 通信层。

**问：Reactor 模型是什么？**

答：Reactor 是事件驱动网络模型。连接、读、写等 IO 事件由事件循环线程监听并分发到 handler。Netty 通过 EventLoopGroup 和 ChannelPipeline 实现类似模型。

**问：EventLoop 为什么不能阻塞？**

答：一个 EventLoop 通常负责多个 Channel。如果在 EventLoop 中执行耗时业务，会阻塞同一线程上的所有连接读写，造成延迟放大和心跳超时。

### 可继续发散

**问：Netty 的 boss 和 worker 是什么？**

答：服务端 boss 线程组负责接收连接，worker 线程组负责已建立连接的读写事件。客户端通常只需要 worker 线程组处理连接和 IO。

**问：ChannelPipeline 的作用是什么？**

答：pipeline 是 handler 链，请求入站和出站会按顺序经过不同 handler。本项目把协议编解码、心跳、业务处理拆到不同 handler，职责更清晰。

**问：ChannelHandler 是线程安全的吗？**

答：不一定。默认 handler 实例如果被多个 Channel 共享，就要保证线程安全；否则应每个 Channel 创建独立 handler。标注 `@Sharable` 前必须确认内部状态线程安全。

**问：sync 和 async 在 Netty 里怎么理解？**

答：Netty 操作通常返回 `ChannelFuture`，本质是异步。调用 `sync()` 会阻塞当前线程等待完成。框架启动或建连时可以同步等待，业务高频路径应避免阻塞 IO 线程。

## 8. TCP、粘包拆包、心跳与重连

### 项目中对应的位置

相关代码：

```text
RpcProtocolEncoder
RpcProtocolDecoder
RpcHeartbeat
HeartbeatHandler
ReconnectHandler
ServerHeartbeatHandler
```

RPC 底层基于 TCP。TCP 是字节流协议，没有天然消息边界，所以必须靠协议字段解决粘包拆包。

### 必须会答

**问：什么是 TCP 粘包拆包？**

答：TCP 只保证字节流有序可靠，不保证一次 write 对应一次 read。多个消息可能粘在一起，一个消息也可能被拆成多次读取。自定义协议需要通过固定头和 body length 解决边界。

**问：心跳解决什么问题？**

答：心跳用于发现空闲连接是否仍然可用，及时识别对端断开、网络异常或连接半开。它不代表业务处理能力，只代表连接层健康。

**问：为什么心跳不走业务线程池？**

答：心跳是连接健康检查，不是业务请求。业务线程池满时心跳如果也排队，会造成客户端误判连接断开。当前项目让心跳绕过业务线程池，保持连接健康判断独立。

### 可继续发散

**问：TCP 和 UDP 区别？**

答：TCP 面向连接、可靠、有序、有拥塞控制；UDP 无连接、不保证可靠和顺序，但开销低。RPC 通常选择 TCP，因为需要可靠请求响应。

**问：TCP 三次握手和四次挥手是什么？**

答：三次握手用于建立连接并确认双方收发能力；四次挥手用于双方分别关闭发送方向。RPC 连接池复用 TCP 连接，避免每次调用都握手。

**问：什么是半连接、半开连接？**

答：半连接通常指握手未完成的连接；半开连接通常指一端认为连接还在，另一端已断开或网络不可达。心跳可以帮助发现半开连接。

**问：重连要注意什么？**

答：重连不能无限高频，否则故障时会形成连接风暴。应有退避、最大次数、共享调度器和关闭状态检查。本项目引入共享 scheduler 是为了避免每个连接都创建自己的调度线程。

## 9. ByteBuf、直接内存与零拷贝

### 项目中对应的位置

相关代码：

```text
RpcProtocolEncoder
RpcProtocolDecoder
```

Netty 使用 `ByteBuf` 作为字节缓冲区。协议编解码必须正确读写和释放。

### 必须会答

**问：ByteBuf 相比 ByteBuffer 有什么优势？**

答：ByteBuf 有独立 readerIndex 和 writerIndex，读写更方便；支持池化、引用计数、堆内和直接内存；在 Netty pipeline 中使用更高效。

**问：直接内存有什么优缺点？**

答：直接内存减少 JVM 堆和内核态之间的拷贝，适合网络 IO；缺点是分配释放成本较高，泄漏后不容易被 GC 直接管理，需要关注 Netty 泄漏检测。

**问：ByteBuf 为什么会泄漏？**

答：池化 ByteBuf 使用引用计数。如果异常路径没有 release，引用计数不归零，内存无法归还池。解码异常、提前 return、异步跨线程传递都是风险点。

### 可继续发散

**问：零拷贝是什么？**

答：广义上是减少用户态和内核态之间的数据复制。Netty 中也有 CompositeByteBuf、FileRegion 等减少拷贝的能力。RPC 普通请求主要关注 ByteBuf 池化和减少不必要数组复制。

**问：怎么排查直接内存泄漏？**

答：打开 Netty leak detector，观察 direct memory、GC 日志和堆外内存；重点检查异常路径和自定义 decoder。

## 10. 自定义协议设计

### 项目中对应的位置

相关代码：

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

协议头携带 magic、version、serializerType、messageType、requestId 等字段，body 承载请求、响应或心跳。

### 必须会答

**问：为什么要自定义协议？**

答：RPC 需要携带 requestId、消息类型、序列化类型、协议版本等元信息。自定义协议可以减少冗余，并让编解码、心跳、响应匹配更可控。

**问：magic number 有什么用？**

答：快速识别非法请求或协议不匹配，避免把非 RPC 数据当成 RPC 消息继续解码。

**问：version 有什么用？**

答：用于协议升级和兼容判断。未来协议字段变化时，可以根据 version 做兼容解析或拒绝不兼容请求。

### 可继续发散

**问：requestId 为什么放 header？**

答：requestId 是请求响应匹配的核心字段，放 header 可以在不完全理解业务 body 的情况下完成响应关联和日志定位。

**问：serializerType 为什么放 header？**

答：解码 body 前必须知道用哪个序列化器，所以 serializerType 必须在 header 中，而不能放在 body 里。

**问：协议如何做兼容？**

答：通过 version、预留字段、可选字段、默认值和灰度升级策略。生产级协议还要考虑双端版本不一致和滚动发布。

## 11. 序列化与对象兼容性

### 项目中对应的位置

相关代码：

```text
Serializer
SerializerType
SerializerFactory
JsonSerializer
KryoSerializer
HessianSerializer
ProtobufSerializer
JavaSerializer
```

RPC 请求跨进程传输 Java 对象，必须序列化为字节数组。

### 必须会答

**问：项目支持哪些序列化？**

答：项目提供了 JSON、Kryo、Hessian、Protobuf、Java 原生序列化等实现，并通过 SPI / factory 选择具体序列化器。

**问：为什么不推荐 Java 原生序列化？**

答：性能较差，序列化结果体积较大，并且历史上有较多反序列化安全问题。生产级 RPC 通常会选择更可控的序列化方案。

**问：Protobuf 的优势是什么？**

答：Protobuf 体积小、性能好、跨语言能力强，适合高性能 RPC。但它需要 schema 管理，对动态对象和复杂 Java 类型不如 JSON 灵活。

### 可继续发散

**问：JSON 的优势和问题？**

答：JSON 可读性强、调试方便、生态丰富；问题是体积大、性能一般、类型信息不足，复杂泛型和多态处理要额外配置。

**问：Kryo 的问题？**

答：Kryo 性能较好，但类注册、线程安全、版本兼容都要谨慎处理。通常不能简单全局共享一个非线程安全序列化实例。

**问：序列化兼容性怎么做？**

答：字段新增要提供默认值，字段删除要兼容旧数据，字段类型变更要谨慎。生产中通常配合版本号、schema 管理和灰度发布。

**问：反序列化安全怎么考虑？**

答：不要反序列化不可信数据；避免 Java 原生反序列化；使用白名单、限制类型、限制 body 大小，并在网关或协议层做鉴权。

## 12. ZooKeeper 注册中心

### 项目中对应的位置

相关代码：

```text
ZooKeeperRegistryImpl
ZkClient
ZkClientFactory
ZooKeeperClientAdapter
ServiceRegistry
ServiceDiscovery
ServiceDirectory
```

provider 向 ZooKeeper 注册临时节点，consumer 订阅节点变化并维护本地服务实例快照。

### 必须会答

**问：为什么用 ZooKeeper 做注册中心？**

答：ZooKeeper 支持临时节点、watcher 和强一致的元数据协调，适合保存服务实例列表。provider 掉线后 session 失效，临时节点会自动删除，consumer 能感知实例下线。

**问：临时节点和持久节点区别？**

答：临时节点和 session 绑定，session 断开或过期后自动删除；持久节点不会随 session 消失。服务实例注册通常用临时节点。

**问：watcher 是一次性的吗？**

答：ZooKeeper 原生 watcher 触发后通常需要重新注册。否则只收到一次变更，后续节点变化可能丢失。

### 可继续发散

**问：ZooKeeper 的 CAP 属于什么？**

答：ZooKeeper 更偏 CP，保证一致性和分区容错。网络分区时可能牺牲可用性，例如少数派无法对外提供写服务。

**问：ZAB 协议是什么？**

答：ZAB 是 ZooKeeper 的原子广播协议，用于 leader 选举和事务日志复制，保证写请求顺序一致。

**问：ZooKeeper 为什么不适合存高频业务数据？**

答：ZooKeeper 适合小规模元数据协调，不适合高频、大数据量读写。RPC 调用不能每次查 ZK，要通过本地缓存和 watcher 更新。

**问：session expired 怎么办？**

答：provider 需要重新注册临时节点，consumer 需要恢复 watcher 和服务目录订阅。否则会出现服务没有重新暴露或本地缓存不更新。

## 13. ServiceDirectory 本地缓存与 Redis 类比追问

### 项目中对应的位置

相关代码：

```text
ServiceDirectory
ServiceDiscoveryCache
ServiceInstancesSnapshot
```

本项目没有使用 Redis 缓存服务列表，而是在 consumer 内存中维护服务实例快照。

### 必须会答

**问：项目有没有用 Redis？**

答：当前没有用 Redis。服务发现使用 ZooKeeper，本地高频读取使用 `ServiceDirectory` 内存缓存。Redis 可以作为其他场景的缓存或分布式协调组件，但不是当前注册发现链路的一部分。

**问：为什么服务发现要做本地缓存？**

答：RPC 调用是高频路径，如果每次都访问注册中心，会增加延迟并压垮注册中心。本地缓存快照可以让调用路径只读内存，注册中心只负责变更通知。

**问：缓存过期后怎么办？**

答：`ServiceDirectory` 会 refresh 最新服务实例；如果注册中心短暂异常且允许 stale fallback，则回退到旧快照，避免服务发现抖动导致业务瞬间全失败。

### Redis 发散追问

**问：如果要用 Redis，可能用在哪里？**

答：可以用于业务数据缓存、配置缓存、分布式限流、分布式锁、服务元数据缓存。但注册中心更推荐 ZK/Nacos/Etcd，因为它们原生支持服务发现语义、临时节点或健康检查。

**问：Redis 常见数据结构有哪些？**

答：String、Hash、List、Set、Sorted Set、Bitmap、HyperLogLog、Stream、Geo。缓存服务实例可用 Hash 或 Set，但要额外处理过期、健康检查和变更通知。

**问：Redis 如何实现分布式锁？**

答：常见做法是 `SET key value NX PX expireMillis` 加锁，释放时用 Lua 脚本校验 value 后删除，避免误删其他客户端的锁。生产级还要考虑锁续期、业务超时、主从切换和 RedLock 争议。

**问：Redis 哨兵和集群区别？**

答：哨兵主要解决主从高可用和故障转移；Redis Cluster 解决数据分片和水平扩展。哨兵不做自动分片，Cluster 按 slot 分片。

**问：Redis 缓存可能有哪些问题？**

答：缓存穿透、缓存击穿、缓存雪崩、热点 key、大 key、缓存一致性、内存淘汰、主从延迟。类比本项目的服务目录，也要防止注册中心抖动时全量请求打到后端。

**问：为什么本项目不用 Redis 做服务注册？**

答：不是不能做，而是不如 ZK/Nacos/Etcd 贴合。服务注册需要实例上下线感知、会话失效、watcher/订阅、健康检查等语义。Redis 需要自己补这些机制，复杂度和可靠性风险更高。

## 14. 负载均衡与节点选择

### 项目中对应的位置

相关代码：

```text
LoadBalancer
LoadBalancerFactory
RandomLoadBalancer
RoundRobinLoadBalancer
ConsistentHashLoadBalancer
LeastConnectionsLoadBalancer
RpcServiceResolver
```

consumer 通过服务目录拿到实例列表后，交给负载均衡器选择 provider 地址。

### 必须会答

**问：项目支持哪些负载均衡？**

答：随机、轮询、一致性哈希、最少连接。不同策略适合不同场景。

**问：轮询有什么问题？**

答：普通轮询默认认为每个节点能力相同。如果节点性能不同，可能把相同流量打到弱节点。生产级可引入权重或动态权重。

**问：一致性哈希适合什么场景？**

答：适合希望相同 key 尽量落到同一节点的场景，比如节点本地缓存命中。节点增删时，一致性哈希能减少 key 迁移范围。

### 可继续发散

**问：最少连接如何实现？**

答：为每个地址维护 active/inflight 计数，优先选择当前请求数少的实例。当前项目修正了计数语义：只有实例真正被熔断允许并选中后才增加计数，finally 中释放。

**问：为什么不能在候选阶段就给 least-connections 加计数？**

答：因为候选实例可能被实例级熔断拒绝或最终没有真实发送请求。如果提前加计数，会造成计数泄漏，后续负载均衡误以为该实例很忙。

**问：负载均衡和熔断怎么结合？**

答：负载均衡不应该把流量打到已经熔断打开的实例。实例级熔断用于过滤坏节点，服务级熔断用于服务整体快速失败。

**问：生产级负载均衡还需要什么？**

答：权重、预热、标签路由、同机房优先、一致性哈希虚拟节点、慢启动、异常节点摘除、动态权重和灰度发布。

## 15. 熔断器设计

### 项目中对应的位置

相关代码：

```text
CircuitBreaker
CircuitBreakerImpl
CircuitBreakerManager
ConsumerCircuitBreakerFilter
RpcClientInvocationExecutor
CircuitBreakerScope
```

项目同时存在服务级熔断和实例级熔断。

### 必须会答

**问：熔断器有哪些状态？**

答：常见状态是 `CLOSED`、`OPEN`、`HALF_OPEN`。关闭状态正常放行；打开状态快速失败；半开状态放少量探测请求，成功恢复，失败重新打开。

**问：服务级熔断和实例级熔断为什么都需要？**

答：服务级熔断判断整个服务是否健康，实例级熔断判断某个 provider 节点是否健康。一个实例坏了不代表整个服务坏了；整个服务都失败时也不能只靠实例级判断。

**问：熔断和降级区别？**

答：熔断是判断是否继续调用下游；降级是调用失败或不可用时返回兜底结果。熔断偏保护机制，降级偏业务兜底策略。

### 可继续发散

**问：熔断统计窗口怎么设计？**

答：可以用固定窗口、滑动窗口、环形数组、时间轮等。固定窗口简单但边界不平滑；滑动窗口更准确但实现复杂。当前项目偏教学实现，生产级可参考 Sentinel 的滑动窗口。

**问：半开状态为什么要限制探测并发？**

答：如果半开状态一下放进大量请求，可能在下游刚恢复时再次打垮它。半开探测应该少量、受控。

**问：熔断阈值怎么调？**

答：要看错误率、慢调用比例、最小请求数、窗口长度和业务 SLA。阈值过低容易误熔断，过高则保护不及时。

## 16. 限流设计

### 项目中对应的位置

相关代码：

```text
FixedWindowRateLimiter
RateLimiterManager
ProviderRateLimitFilter
RpcClientInvocationExecutor
```

项目支持 consumer 侧限流和 provider 侧限流。

### 必须会答

**问：为什么 consumer 和 provider 都要限流？**

答：consumer 限流保护调用方和下游，避免自身无限堆积；provider 限流保护服务端业务线程池和核心资源。二者保护对象不同。

**问：固定窗口限流有什么问题？**

答：窗口边界可能产生瞬时双倍流量。例如上一窗口末尾和下一窗口开头都打满阈值。生产级可以使用滑动窗口、令牌桶或漏桶。

**问：令牌桶和漏桶区别？**

答：令牌桶按速率生成令牌，允许一定突发流量；漏桶按固定速率出水，更平滑但对突发不友好。

### 可继续发散

**问：限流发生在调用链哪个位置比较合理？**

答：应尽量放在昂贵操作前。provider 侧限流要在业务执行前；同时 metrics 要包住限流，确保限流拒绝也能被统计。

**问：单机限流和分布式限流区别？**

答：单机限流只限制当前进程，简单高效；分布式限流限制整个集群，需要 Redis、网关或集中式组件协调，性能和可用性成本更高。

**问：如果用 Redis 做分布式限流怎么做？**

答：可以用 Lua 脚本实现计数器、滑动窗口或令牌桶，保证读写原子性。要注意 Redis 延迟、单点热点、集群 slot、时钟和脚本耗时。

## 17. 重试、幂等与重试风暴

### 项目中对应的位置

相关代码：

```text
RetryExecutor
DefaultRetryStrategy
FailOverClusterInvoker
FailFastClusterInvoker
RpcClientInvocationExecutor
```

项目通过 cluster 策略决定是否 failover 重试。

### 必须会答

**问：哪些失败适合重试？**

答：网络抖动、连接短暂失败、服务端繁忙、半开探测竞争等短暂故障适合有限重试。参数错误、序列化失败、明确业务异常、非幂等写操作不适合重试。

**问：什么是幂等？**

答：同一个请求执行一次和执行多次的结果一致，或者多次执行不会造成额外副作用。读请求通常更容易幂等，写请求需要业务幂等号、去重表或状态机保证。

**问：什么是重试风暴？**

答：下游已经故障时，大量上游同时重试，把原本一倍流量放大成多倍，进一步压垮下游。重试要配合超时、限流、熔断和退避。

### 可继续发散

**问：重试应该在客户端还是服务端做？**

答：RPC 调用重试通常在客户端做，因为客户端知道调用配置、幂等语义和可选实例。服务端内部重试更适合访问自己的下游资源。

**问：重试要不要换实例？**

答：failover 语义通常应该换实例，避免持续打到坏节点；但如果是一致性哈希场景，是否换实例要看业务是否依赖缓存亲和。

**问：重试次数怎么配？**

答：默认要小，通常 0 到 2 次。还要看总超时时间，不能每次 attempt 都用完整超时导致总延迟不可控。

## 18. 降级与业务兜底

### 项目中对应的位置

相关代码：

```text
DegradationPolicy
FailFastDegradation
DefaultValueDegradation
DegradationPolicyFactory
```

降级用于调用失败或不可用时返回业务可接受的结果。

### 必须会答

**问：降级是不是成功？**

答：不是。降级是兜底，不是真实调用成功。metrics 和日志中要能体现降级，否则线上会误判系统健康。

**问：哪些接口适合降级？**

答：推荐列表、非核心展示、可容忍旧数据或默认值的查询接口适合降级。支付、下单、扣库存等强一致写操作通常不适合简单降级为成功。

**问：降级和缓存有什么关系？**

答：降级可以返回默认值，也可以返回缓存旧值。若使用缓存旧值，要标明数据新鲜度，避免用户误以为是实时结果。

### 可继续发散

**问：如何设计 fallback？**

答：fallback 应该简单、可靠、无复杂依赖。如果 fallback 又依赖另一个不稳定服务，故障时可能继续放大问题。

**问：降级会不会掩盖问题？**

答：会。如果没有日志、metrics 和告警，降级会让用户暂时无感，但系统真实故障被掩盖。因此降级必须可观测。

## 19. Spring、Bean 生命周期与 Starter

### 项目中对应的位置

相关代码：

```text
rpc-spring/src/main/java/com/rpc/spring/EnableRpc.java
rpc-spring/src/main/java/com/rpc/spring/RpcSpringRegistrar.java
rpc-spring/src/main/java/com/rpc/spring/RpcSpringManager.java
rpc-spring-boot-starter/src/main/java/com/rpc/spring/boot/RpcSpringBootAutoConfiguration.java
rpc-spring-boot-starter/src/main/java/com/rpc/spring/boot/RpcBootFrameworkProperties.java
```

Spring 层负责扫描 `@RpcService`、注入 `@RpcReference` 代理、接入启动和关闭生命周期。

### 必须会答

**问：Spring Boot Starter 做了什么？**

答：starter 负责自动创建 RPC 相关 Bean、绑定 `application.yml` 配置、推断 scan package、启动 consumer/provider bootstrap，并暴露可观测入口。

**问：BeanPostProcessor 可以做什么？**

答：它可以在 Bean 初始化前后对 Bean 做处理。本项目可以在 Bean 创建阶段扫描字段上的 `@RpcReference`，创建 RPC 代理并注入。

**问：自动装配原理是什么？**

答：Spring Boot 通过自动配置类和条件装配机制，在满足 classpath、配置属性等条件时创建 Bean。配置属性通过 `ConfigurationProperties` 绑定到 Java 对象。

### 可继续发散

**问：为什么 core 不依赖 Spring？**

答：core 是 RPC 框架基础能力，应能在非 Spring 环境运行。Spring 只是接入层，如果 core 依赖 Spring，会降低复用性并污染模块边界。

**问：Spring Bean 生命周期有哪些阶段？**

答：实例化、属性填充、Aware 回调、BeanPostProcessor 前置处理、初始化方法、BeanPostProcessor 后置处理、使用、销毁。RPC 代理注入和服务注册通常发生在 Bean 后处理或上下文刷新阶段。

**问：循环依赖会影响代理注入吗？**

答：可能。Spring 处理循环依赖依赖三级缓存和提前暴露引用，复杂代理场景容易产生早期引用和最终代理不一致问题。RPC 引用字段注入要尽量避免复杂循环依赖。

**问：scan package 为什么可以不配？**

答：starter 可以优先读取显式配置，如果没有则用 Spring Boot 的 AutoConfigurationPackages 推断主启动类所在包，降低用户接入成本。

## 20. SPI、类加载与扩展点

### 项目中对应的位置

相关代码：

```text
ExtensionLoader
ExtensionFactory
SPI
Inject
Initialize
SerializerFactory
LoadBalancerFactory
```

SPI 用于序列化器、负载均衡器、过滤器等扩展点。

### 必须会答

**问：Java SPI 是什么？**

答：Java SPI 是 Service Provider Interface 机制，通过配置文件声明接口实现，运行时由类加载器加载实现类。它用于让框架发现第三方扩展。

**问：为什么 RPC 框架需要 SPI？**

答：因为序列化、负载均衡、过滤器等策略不应该写死。SPI 让框架核心流程固定，策略实现可替换。

**问：SPI 加载要注意什么？**

答：要考虑缓存、并发安全、默认实现、加载失败清理、依赖注入、初始化顺序、循环依赖和类加载器问题。

### 可继续发散

**问：Java SPI 和 Dubbo SPI 有什么区别？**

答：Java SPI 会一次性加载所有实现，不支持按 name 精确获取、依赖注入、自适应扩展等高级能力；Dubbo SPI 增强了按名加载、默认扩展、自适应扩展和扩展注入。

**问：为什么扩展实例要缓存？**

答：很多扩展是无状态或可复用的，每次调用都反射创建会增加开销。缓存可以减少对象创建和初始化成本。但有状态扩展要谨慎共享。

**问：循环依赖怎么处理？**

答：扩展 A 注入 B，B 又注入 A 时会循环创建。可以维护线程内创建链路检测循环依赖，并在失败时清理半初始化状态。

## 21. 配置设计、默认值与动态配置

### 项目中对应的位置

相关代码：

```text
RpcFrameworkConfig
RpcClientConfig
RpcServerConfig
RpcConfigKeys
RpcClientConfigBinder
RpcServerConfigBinder
RpcBootFrameworkProperties
```

项目将用户配置、客户端运行时配置和服务端运行时配置分层。

### 必须会答

**问：为什么要区分 FrameworkConfig、ClientConfig 和 ServerConfig？**

答：FrameworkConfig 是用户视角的全局配置，ClientConfig 和 ServerConfig 是运行时视角，分别服务于客户端 transport 和服务端 runtime。这样可以避免 bootstrap 臃肿，也避免底层模块直接依赖 Spring Boot 配置类。

**问：默认值应该怎么设计？**

答：默认值要保证最小可用，同时不能隐藏生产风险。比如 example 可以只配注册中心地址，但 pending 上限、连接数、线程池、超时、重试都要支持显式覆盖。

**问：为什么要做参数下限保护？**

答：线程数、队列大小、连接数如果配置为 0 或负数，会导致运行时异常或不可用。框架应在配置转换时修正或明确失败。

### 可继续发散

**问：动态配置中心怎么接？**

答：可以接 Nacos、Apollo、ZooKeeper 节点监听等。配置变更后刷新限流阈值、熔断阈值、超时和重试参数。线程池核心参数也可动态改，但队列容量通常不容易直接变更。

**问：哪些参数适合动态调整？**

答：限流阈值、熔断开关、熔断阈值、超时时间、重试次数、服务权重、路由规则较适合动态调整。协议类型、服务端端口、序列化方式这类影响连接兼容的参数要谨慎。

**问：配置优先级怎么设计？**

答：通常是方法级 > 服务级 > 全局默认；运行时动态配置 > 启动静态配置；显式配置 > 默认值。项目里方法级配置会解析成 `InvocationOptions`，再写入 request attachments。

## 22. 可观测性：日志、指标、Trace

### 项目中对应的位置

相关代码：

```text
ServiceMetrics
ServiceMetricsManager
ClientRuntimeMetrics
ClientRuntimeMetricsManager
ServerRuntimeMetrics
ObservabilitySnapshot
RpcObservabilityEndpoint
```

项目通过 metrics、MDC 和 observability endpoint 提供基本可观测能力。

### 必须会答

**问：为什么非 200 响应要计为失败？**

答：因为限流、服务繁忙、熔断、降级等可能以 response code 表达。如果只统计异常，不统计非 200，会把失败误判为成功。

**问：provider metrics 为什么要包住 rate limit？**

答：否则限流短路后 metrics 看不到拒绝请求。metrics 包住 rate limit，才能统计成功、异常、限流和繁忙。

**问：日志、metrics、trace 区别是什么？**

答：日志用于记录离散事件，metrics 用于聚合数值观测，trace 用于串联一次请求跨服务的调用路径。三者互补。

### 可继续发散

**问：生产级可观测怎么做？**

答：metrics 接 Prometheus/Grafana，trace 接 OpenTelemetry/Jaeger，日志接 ELK 或 Loki，并统一 traceId。还要加错误码维度、实例维度、接口维度和耗时分位数。

**问：平均耗时为什么不够？**

答：平均值会掩盖长尾。RPC 更应关注 P95、P99、P999，因为用户体验和超时通常由长尾决定。

**问：高基数标签有什么问题？**

答：如果把 requestId、用户 ID 这类高基数字段作为 metrics label，会导致时序数量爆炸，压垮监控系统。traceId 适合日志和 trace，不适合作为 metrics 标签。

## 23. 客户端背压与资源预算

### 项目中对应的位置

相关代码：

```text
RequestManager
ConnectionPool
RpcConnection
ClientRuntimeMetricsManager
ClientOverloadedException
```

客户端通过 pending、inflight 和连接数限制保护自身。

### 必须会答

**问：什么是背压？**

答：背压是系统在下游处理不过来时，向上游反馈压力，限制继续接收或发送请求，避免无限堆积导致系统崩溃。

**问：pending 和 inflight 区别？**

答：pending 是客户端已经发出或准备等待响应的总请求；inflight 是某个连接上正在处理的未完成请求。pending 是全局请求预算，inflight 是连接级并发预算。

**问：为什么要限制总连接数？**

答：连接本身消耗 fd、内存和心跳资源。故障或服务实例很多时，如果不限制总连接数，客户端可能因为连接膨胀拖垮自己。

### 可继续发散

**问：连接池和线程池有什么相似点？**

答：都是资源池，都需要最大容量、空闲回收、关闭清理、拒绝策略和监控指标。连接池控制网络资源，线程池控制执行资源。

**问：请求超时后响应又回来了怎么办？**

答：pending 表中 future 已经被超时清理，迟到响应应被丢弃或记录日志，不能重新完成一个已经失败的调用。

**问：channel inactive 时为什么要批量失败 pending？**

答：连接断开后该 channel 上的未完成请求不可能再收到有效响应。如果不批量失败，调用方会一直等到超时，资源也会滞留。

## 24. 错误码、异常映射与失败语义

### 项目中对应的位置

相关代码：

```text
ErrorCode
RpcException
RpcExceptionMapper
ClientOverloadedException
CircuitBreakerException
TimeoutException
RpcResponse
```

项目通过统一错误码和异常映射表达失败类型。

### 必须会答

**问：为什么需要错误码？**

答：异常在跨进程后不能简单原样传递。错误码让 consumer 能区分超时、限流、服务繁忙、熔断、服务不存在等失败，并决定是否重试或降级。

**问：本地异常和远程异常怎么区分？**

答：本地异常发生在 consumer 进程，如序列化失败、连接池超限；远程异常由 provider 返回 response code 表达，如服务端繁忙、服务不存在、业务执行异常。

**问：为什么 response code 非 200 要转异常？**

答：这样 cluster、retry、circuit breaker 和 metrics 都能用统一失败语义处理，而不是每层都单独判断 response code。

### 可继续发散

**问：业务异常应该怎么返回？**

答：简单项目可以作为 `SERVER_ERROR` 返回；生产级要区分框架异常和业务异常，并考虑异常类型白名单、错误码体系和安全脱敏。

**问：异常信息能直接透传吗？**

答：生产环境不建议直接透传堆栈或内部实现细节，可能泄露敏感信息。可以返回错误码和简短 message，详细堆栈留在服务端日志。

## 25. 压测、调优与故障定位

### 项目中对应的位置

相关模块：

```text
ConnectionPool / RequestManager
BizThreadPool
ServiceMetricsManager
ClientRuntimeMetricsManager
CircuitBreakerManager
RateLimiterManager
```

压测不是只看 QPS，还要看延迟分位数、错误码、线程池队列、连接数、pending、CPU、内存、GC、网络和注册中心状态。

### 必须会答

**问：压测先看哪些指标？**

答：QPS、P95/P99 延迟、成功率、timeout、CLIENT_BUSY、SERVER_BUSY、熔断次数、限流次数、pending 数、inflight 数、连接数、服务端线程池队列长度、CPU、内存和 GC。

**问：CLIENT_BUSY 多说明什么？**

答：客户端请求预算不足或下游响应太慢。要看 pending 上限、单连接 inflight、连接数、超时时间和 provider 响应耗时。

**问：SERVER_BUSY 多说明什么？**

答：provider 业务线程池或队列打满，可能是线程数太小、队列太小、业务耗时过长或限流阈值过高。

### 可继续发散

**问：timeout 多怎么排查？**

答：先看 provider 是否 SERVER_BUSY，再看业务耗时和 GC；再看客户端 pending 是否堆积、连接数是否不足、网络是否抖动；最后检查 readTimeout 是否过短。

**问：如何调线程池？**

答：先确定业务类型。CPU 密集看 CPU 利用率和上下文切换；IO 密集看等待时间和队列。逐步增加线程数和队列容量，观察 P99 和拒绝率，不要盲目加大队列。

**问：如何调连接池？**

答：先看单连接 inflight 是否打满，再增加单地址连接数；如果 provider 实例多，再控制总连接数，避免连接资源膨胀。连接数不是越多越好，过多会增加上下文和心跳开销。

**问：为什么不能只用平均响应时间判断性能？**

答：平均值会掩盖长尾。RPC 超时和用户体验通常由 P99 决定。压测必须看分位数和错误码分布。

## 26. JVM、GC 与性能问题

### 项目中的关联点

RPC 框架高并发下会产生请求对象、响应对象、序列化字节数组、future、日志上下文等对象。Netty 还会使用直接内存。

### 必须会答

**问：RPC 项目为什么要关注 GC？**

答：GC 停顿会导致请求处理暂停、心跳延迟、响应超时和熔断误判。压测时如果 P99 抖动明显，要检查 GC 日志、对象分配速率和直接内存。

**问：堆内存和直接内存区别？**

答：堆内存由 JVM GC 管理，普通 Java 对象在堆上；直接内存位于堆外，Netty 常用于网络 IO。直接内存也需要释放和监控，否则可能 OOM。

**问：如何减少 RPC 调用对象分配？**

答：避免不必要的临时对象和大字符串日志；复用序列化器中可复用的安全对象；控制请求 body 大小；使用池化 ByteBuf；谨慎做对象池，避免复杂度大于收益。

### 可继续发散

**问：年轻代 GC 和老年代 GC 对 RPC 有什么影响？**

答：年轻代 GC 频繁会造成短暂停顿和 CPU 消耗；老年代 GC 停顿更长，可能导致大量请求超时。RPC 高并发下大量短生命周期对象会增加年轻代压力。

**问：如何选择 GC？**

答：现代 Java 服务常用 G1 或 ZGC。G1 适合通用低停顿需求，ZGC 更适合大堆低停顿场景。选择要结合 JDK 版本、堆大小和延迟目标。

**问：日志会影响性能吗？**

答：会。高频路径打印大量同步日志会增加 IO 和锁竞争。RPC 框架应避免在每次成功请求上打印 info 日志，异常和慢请求日志要采样或分级。

## 27. 安全、鉴权与序列化风险

### 项目中的关联点

当前项目以核心链路学习为主，尚未完整实现生产级安全治理。

### 必须会答

**问：RPC 框架需要哪些安全能力？**

答：服务鉴权、调用方身份认证、TLS 加密、请求签名、防重放、序列化白名单、最大包大小限制、接口级权限和敏感日志脱敏。

**问：为什么反序列化有安全风险？**

答：如果反序列化不可信输入，攻击者可能构造恶意对象触发 gadget 链，造成远程代码执行或资源耗尽。Java 原生反序列化尤其需要谨慎。

**问：如何防止大包攻击？**

答：协议层限制 body size，超过上限直接拒绝；连接层设置读超时；业务层限制参数大小；日志中不要打印完整大 payload。

### 可继续发散

**问：TLS 会影响性能吗？**

答：会增加握手和加解密开销，但可以通过连接复用、会话复用、硬件加速和合理 cipher 降低影响。内网 RPC 是否启用 TLS 取决于安全要求。

**问：如何做服务鉴权？**

答：可以在协议 header 或 attachments 中携带 token、签名或 mTLS 身份，在 provider filter 中校验。鉴权失败应在进入业务线程池前尽早拒绝。

## 28. 分布式系统基础：CAP、一致性与可用性

### 项目中的关联点

ZooKeeper 注册发现、consumer 本地缓存、stale fallback、熔断降级都涉及一致性和可用性取舍。

### 必须会答

**问：CAP 怎么理解？**

答：分布式系统在网络分区存在时，无法同时保证强一致性和高可用性。ZooKeeper 更偏 CP；很多缓存系统更偏 AP 或最终一致。

**问：服务发现需要强一致吗？**

答：不一定每次调用都需要强一致。服务实例列表允许短暂最终一致，因为实例上下线本身也有传播延迟。项目通过本地快照和 watcher 保证最终更新，注册中心异常时可短暂使用旧快照。

**问：旧快照会不会打到已下线实例？**

答：可能，所以调用失败后要依赖连接失败、超时、实例级熔断和负载均衡摘除来收敛。stale fallback 是可用性优先的取舍，不是强一致方案。

### 可继续发散

**问：一致性哈希和 CAP 有关系吗？**

答：它们解决的问题不同。一致性哈希是数据或请求分布算法；CAP 是分布式系统在网络分区下的一致性和可用性取舍。

**问：如何避免注册中心雪崩？**

答：consumer 本地缓存、watcher 订阅、single flight 刷新、失败回退旧快照、注册中心连接限流、启动预热和指数退避。

## 29. 与 Dubbo、gRPC、Spring Cloud 的对比

### 必须会答

**问：你的项目和 Dubbo 有什么区别？**

答：本项目实现了 RPC 核心链路和稳定性治理闭环，包括代理、协议、序列化、注册发现、负载均衡、限流、熔断、降级、重试、线程隔离、metrics 和 starter。但 Dubbo 是生产级生态，包含更完整的服务治理、路由、配置中心、多协议、泛化调用、异步调用、监控和生态集成。

**问：和 gRPC 区别？**

答：gRPC 基于 HTTP/2 和 Protobuf，跨语言、流式调用和标准化更强。本项目是自研 Java RPC，更适合学习 RPC 核心原理和 Java 生态集成。

**问：和 Spring Cloud OpenFeign 区别？**

答：Feign 偏 HTTP 声明式调用，集成 Spring Cloud 生态；本项目偏自定义 TCP 协议 RPC，关注协议、连接池、编解码和 Netty 通信。

### 可继续发散

**问：为什么不用 HTTP？**

答：HTTP 生态成熟、调试方便、跨语言强；自定义 TCP 协议更可控，能减少部分冗余并自定义 requestId、序列化和心跳。选择取决于场景，不是绝对优劣。

**问：生产中会选自研还是成熟框架？**

答：核心业务生产环境通常优先成熟框架，因为稳定性、生态、监控和社区验证更充分。自研更适合学习、特定场景优化或已有框架无法满足的需求。

## 30. 如果面试官深挖“项目没做”的内容

### 回答原则

不要把没做的说成做了。正确回答是：

```text
当前项目没有完整实现这个能力。
我在设计上预留了哪些边界。
如果要做生产级增强，我会怎么接入。
这个能力会影响哪些模块。
```

### 常见追问

**问：有没有动态配置中心？**

答：当前主要是启动时配置绑定，方法级配置会解析到 `InvocationOptions`。生产增强可以接 Nacos/Apollo/ZK watcher，动态刷新限流、熔断、超时、重试、权重和路由。

**问：有没有灰度发布？**

答：当前没有完整灰度发布。可以在服务实例元数据中加入 version、group、tag，在负载均衡前增加 router filter，根据调用方标签和灰度规则筛选实例。

**问：有没有服务鉴权？**

答：当前没有完整实现。可在协议 attachments/header 中加入 token、签名或 mTLS 身份，在 provider filter 的早期阶段校验，失败直接拒绝。

**问：有没有异步调用？**

答：当前核心请求响应内部有 pending future 机制，但对用户暴露的完整异步 Future API 不是重点。后续可以在代理层根据返回类型支持 `CompletableFuture<T>`。

**问：有没有泛化调用？**

答：当前主要是接口代理调用。泛化调用可以让调用方不依赖接口 class，直接通过 serviceName、methodName、参数类型和参数值调用，适合网关或测试平台。

## 31. 高频综合题回答模板

### 模板 1：一次 RPC 调用发生了什么？

```text
consumer 通过代理对象拦截接口调用，构造 RpcRequest。
consumer filter 写 trace、MDC 和 metrics。
调用编排器解析方法级配置，执行限流和服务级熔断。
ServiceDirectory 读取本地服务快照，负载均衡选择 provider，并结合实例级熔断过滤坏节点。
每次真实 attempt 前生成 requestId，通过 Netty 连接池发送。
provider 解码后由 RpcRequestHandler 判断消息类型，业务请求进入 BizThreadPool，心跳直接返回。
dispatcher 根据 serviceName 找到本地服务对象，provider filter 做 MDC、metrics、限流，再反射调用真实方法。
响应按 requestId 回到客户端 pending future，最后记录 metrics 和熔断结果。
```

### 模板 2：如何保证高并发稳定？

```text
客户端有 pending 上限、单连接 inflight 上限、单地址连接数和总连接数限制，避免自身无限堆积。
服务端用业务线程池隔离 IO 和业务执行，队列满返回 SERVER_BUSY。
心跳绕过业务线程池，避免业务过载误判连接异常。
限流、熔断、降级、重试共同控制故障扩散。
metrics 统计非 200 响应和异常，压测时可以定位瓶颈。
服务发现用本地快照，避免每次调用打注册中心。
```

### 模板 3：如何说明项目边界？

```text
这个项目实现的是 RPC 核心链路和稳定性治理闭环，不是声称替代 Dubbo。
已经覆盖代理、协议、序列化、注册发现、负载均衡、Netty 通信、限流、熔断、降级、重试、线程隔离、metrics 和 Spring Boot Starter。
生产级还需要动态配置、灰度路由、鉴权、TLS、完整 tracing、Prometheus、异步 API、泛化调用和长时间大规模压测。
```

## 32. 复习优先级

第一优先级：

- 一次完整 RPC 调用链路。
- JDK 动态代理和 provider 反射调用。
- Netty IO 线程为什么不能执行业务。
- requestId 和 traceId 的区别。
- 服务级熔断和实例级熔断区别。
- provider metrics 为什么要包住 rate limit。
- 客户端 pending/inflight/连接数背压。
- ZooKeeper 临时节点、watcher、session expired。

第二优先级：

- ThreadPoolExecutor 参数和拒绝策略。
- ConcurrentHashMap、AtomicInteger、CAS、volatile。
- TCP 粘包拆包、心跳、重连。
- ByteBuf、直接内存、内存泄漏。
- 序列化方案对比和兼容性。
- 负载均衡算法对比。
- 限流算法对比。

第三优先级：

- Redis 类比追问。
- CAP、ZAB、注册中心高可用。
- Spring Boot 自动装配和 Bean 生命周期。
- SPI 和类加载器。
- JVM/GC 与性能抖动。
- 安全、鉴权、TLS。
- 与 Dubbo/gRPC/Spring Cloud 对比。

最终回答策略：

```text
任何八股问题都先落回项目，不要直接背概念。
任何项目没做的能力都说明边界，不要硬编。
任何稳定性问题都从资源边界、失败语义、可观测性和恢复策略四个角度回答。
```
