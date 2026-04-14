# RPC 稳定性优化记录与优化后流程梳理

这份文档记录本轮围绕“让当前系统已有功能高效稳定运行”所做的审查、修复和优化，并在最后重新梳理优化后的整体 RPC 调用流程。

文档重点不是重新讲 RPC 通用原理，而是说明当前项目已经补齐了哪些稳定性链路，以及这些链路现在如何串起来。

---

## 一、修复和优化总览

本轮优化主要集中在以下方向：

- 客户端连接、请求、超时、断链和重连闭环。
- 服务端请求执行模型和过载降级。
- 注册中心和服务发现的恢复、缓存、订阅和启动失败处理。
- 熔断、重试、降级、负载均衡的统计口径和长期状态清理。
- SPI 扩展加载的并发和失败重试能力。
- 可观测性指标和 Spring Boot HTTP 读取入口。
- 全局状态和资源关闭阶段的隔离与回收。

目前整仓回归验证已通过：

```powershell
mvn -q -pl rpc-core test
mvn -q -pl rpc-spring-boot-starter -am test
mvn -q test
```

---

## 二、客户端稳定性优化

### 2.1 重连链路修复

原问题：

- `ReconnectHandler` 初始化时拿到的 `ConnectionPool` 可能还是 `null`，导致断链后不移除旧连接，也不会真正调度重连。
- 服务端正常下线时，客户端仍可能按网络抖动处理，继续无意义地退避重连。
- 每条连接各自持有重连 scheduler，连接数多时线程资源会线性膨胀。

修复后：

- `ReconnectHandler` 改为通过 `Supplier<ConnectionPool>` 延迟获取连接池，避免初始化顺序问题。
- 断线时会先查 `ServiceDirectory.containsAddress(address)`，地址已经从服务目录消失时只清理连接，不再重连。
- 重连 scheduler 改为共享 `ReconnectSharedScheduler`，handler 移除或关闭时释放引用。
- 重连调度、成功、失败都接入客户端运行时指标。

效果：

- 网络抖动场景仍然会自动重连。
- provider 正常摘除/下线时不会制造无效连接尝试和日志噪音。
- 多连接场景不会因为重连 handler 数量增长而线性增加线程。

### 2.2 请求挂起和超时清理

原问题：

- 请求发送前注册到 `RequestManager`，但发送失败、等待超时、channel 异常关闭时不一定能及时清理。
- `clearTimeoutRequests()` 没有接入主路径。
- 断链时缺少按 channel 批量失败挂起请求的能力。
- `maxPendingRequests` 原本是基于 `size()` 的软限制，高并发下可能被突破。

修复后：

- 客户端发送时先拿连接，再注册 pending request，减少建连失败后的无主 future。
- `writeAndFlush()` 失败、`future.get(timeout)` 超时、channel inactive、exceptionCaught 都会清理对应 pending request。
- `RequestManager` 按 channel 记录 requestId，支持只失败某个 channel 上的挂起请求。
- 增加共享 `ClientSharedScheduler`，周期扫描超时请求，不再每个 client 单独起一条扫描线程。
- `maxPendingRequests` 改为 `AtomicInteger` 硬预算，所有清理路径同步维护计数。

效果：

- 连接断开时，调用方能立即收到异常，不必等到 read timeout。
- 连接半失联但未断开时，超时扫描能兜底清理。
- 客户端全局 pending 请求不会在并发高峰下突破预算。

### 2.3 客户端连接池和并发预算

原问题：

- 每个地址只有单连接承载全部并发，热点地址容易被单 channel 限制。
- 单连接没有 in-flight 上限，慢响应或抖动时 pending request 可能堆积。
- 按地址连接池扩容后，空闲连接缺少回收。
- `maxTotalConnections` 原本是软限制，不同地址并发建连时可能突破。

修复后：

- `RpcConnection` 增加单连接 in-flight request 计数和上限。
- `ConnectionPool` 从“每地址单连接”扩展为“每地址小连接池”。
- 当已有连接打满且未达到 `maxConnectionsPerAddress` 时，为该地址创建新连接。
- 连接选择优先选择活跃且更空闲的连接。
- 增加空闲连接 TTL 回收，由共享 `ConnectionPoolSharedScheduler` 扫描。
- `maxTotalConnections` 改为全局原子硬预算。
- 预算打满统一抛 `ClientOverloadedException`，并映射为 `CLIENT_BUSY`。

关键配置：

- `maxInflightRequestsPerConnection`：单连接最大 in-flight 请求数，默认 256。
- `maxConnectionsPerAddress`：单地址最大连接数，默认 2。
- `maxTotalConnections`：客户端总连接数上限，默认 128。
- `maxPendingRequests`：客户端全局 pending 请求上限，默认 10000。
- `idleConnectionTtlMillis`：空闲连接 TTL，默认 60000ms。
- `idleConnectionEvictIntervalMillis`：空闲连接扫描周期，默认 30000ms。

效果：

- 热点 provider 地址可以由多条连接分摊并发。
- 单连接和全局请求预算都有硬保护。
- 热点退去后，多出来的连接会被回收。
- 预算打满时调用方能看到明确的 `CLIENT_BUSY`，而不是混成网络错误。

---

## 三、服务端稳定性优化

### 3.1 服务端 IO 线程不再阻塞业务执行

原问题：

- Netty handler 线程直接调用 request executor。
- `RpcRequestExecutor.execute()` 内部提交业务线程池后又同步 `.get()`，等价于 Netty worker 被业务执行阻塞。

修复后：

- `RpcRequestHandler` 将请求投递给业务执行器后立即返回。
- `RpcRequestExecutor` 负责业务反射调用和响应构造，不再在 IO 线程里阻塞等待内部 future。

效果：

- 慢业务不会直接卡住 Netty IO 线程。
- 服务端吞吐和尾延迟更稳定。

### 3.2 服务端过载返回明确错误

原问题：

- 业务线程池拒绝任务时，连接可能被直接关闭。
- 客户端只能看到断链或 channel 异常，难以区分服务端忙和网络故障。

修复后：

- Netty 服务端线程池拒绝任务时返回 `SERVER_BUSY` 响应。
- Socket 服务端也补齐了同样的过载降级返回。

效果：

- 客户端可以基于 `SERVER_BUSY` 做重试、熔断或降级。
- 服务端过载不再默认表现为连接异常。

### 3.3 服务端绑定地址一致性

原问题：

- Netty/Socket 服务端曾经存在实际监听地址和注册中心发布地址不一致的问题。

修复后：

- 服务端按配置中的 `host` 显式 bind。
- 注册中心发布地址和真实监听地址保持一致。

效果：

- 避免误暴露到所有网卡。
- 避免 consumer 发现的地址和 provider 实际监听地址不一致。

---

## 四、注册中心和服务发现优化

### 4.1 LocalRegistry 隔离

原问题：

- `LocalRegistryImpl` 使用静态全局 `SERVICE_MAP`，同 JVM 多个 provider 或测试实例之间会互相污染。

修复后：

- 改为实例级服务表。

效果：

- 同 JVM 多 provider、多端口、并行测试时不会互相覆盖服务注册。

### 4.2 ZooKeeper 会话恢复

原问题：

- ZK session expired 后只有日志，没有重建连接、恢复已注册临时节点和重新订阅 watcher。
- 初次 `subscribe()` 失败时，listener 可能留在本地 map 中。
- 最后一个 listener 取消订阅后，旧 watcher 事件仍可能继续重挂 watcher。
- 初始连接使用 `latch.await()`，ZK 地址不可达或无连接事件时可能让启动线程永久挂住。

修复后：

- session expired 后自动重建 ZK client。
- 恢复 provider 已注册的临时节点。
- 恢复 consumer 已订阅服务的 watcher。
- 初次订阅失败时回滚刚加入的 listener。
- 无订阅者时，watcher 回调不再继续重挂。
- 初始连接等待增加 `sessionTimeout` 上限，超时后关闭 client 并快速失败。

效果：

- ZK session 过期后能自动恢复。
- 订阅失败不会留下脏状态。
- 无效 watcher 链路不会长期存在。
- 注册中心不可用时，启动不会无限挂起。

### 4.3 ServiceDirectory 缓存和刷新

原问题：

- 服务目录缓存 miss 时可能遍历所有已订阅服务逐个 refresh。
- 地址到服务名的最近映射只增不减。
- 同一个服务并发 refresh 可能放大为多次注册中心访问。
- 订阅回调推送相同快照时会重复打更新日志。

修复后：

- `ServiceDirectory` 增加“地址 -> 最近服务名集合”的映射，断线判断优先只刷新相关服务。
- 最近地址映射增加 TTL 和容量上限，避免扩缩容后无限增长。
- 同一服务 refresh 增加单飞控制，并发 refresh 复用同一个结果。
- 快照真正变化时才打印目录更新日志。

效果：

- 服务上下线、断线判断时注册中心访问更少。
- 长期运行不会积累无限历史地址。
- 高频注册中心事件下日志噪音降低。

---

## 五、熔断、重试、降级和负载均衡优化

### 5.1 服务级和实例级熔断统计口径

确认后的设计语义：

- consumer filter 层维护服务级 breaker，反映用户视角的 cluster 调用最终成功率。
- invoke/地址选择层维护实例级 breaker，反映具体 provider 实例健康度。
- 一次真实远程调用失败，如果已经选中实例并发起调用，服务级和实例级都统计失败是合理的。

修复点：

- `ConsumerCircuitBreakerFilter` 不再硬编码使用全局 `CircuitBreakerManager`。
- `RpcClientInvocationExecutor` 将注入的 `CircuitBreakerManager` 放入 `FilterContext`，服务级和实例级 breaker 使用同一个 manager。
- 补测试锁定统计口径：选实例前失败只记服务级；failover 中间失败但最终成功时，服务级按最终成功统计。

效果：

- 自定义 manager、测试隔离 manager、多 runtime scope 场景不会出现两层 breaker 统计分叉。

### 5.2 半开探测并发竞争

原问题：

- 地址筛选阶段对所有实例调用 `allowRequest()`，可能消耗未被选中实例的 half-open probe 配额。
- `OPEN -> HALF_OPEN` 状态切换和半开计数重置不是完整原子区间，并发下可能多发 probe。

修复后：

- 地址选择改成两段式：先按 state 过滤仍在 OPEN 的实例，再只对最终选中实例调用 `allowRequest()`。
- `CircuitBreakerImpl` 的非 CLOSED 状态路径放入受控临界区，保证状态切换、计数清零和 probe 发放顺序一致。
- `CircuitBreakerException` 增加 reason：
  - `ALL_INSTANCES_OPEN`
  - `HALF_OPEN_PROBE_EXHAUSTED`

效果：

- 半开探测配额只消耗在真正发起请求的实例上。
- 并发半开探测不会突破配置上限。
- 调用侧能区分“全部实例仍在 open”与“half-open 探测位被抢完”。

### 5.3 重试语义收紧

原问题：

- `RetryExecutor` 将普通 `Exception` 一律包装成可重试的 server error，本地编排异常也可能被误重试。
- `InterruptedException` 可能被吞掉。
- 熔断不同 reason 的重试策略不够精细。

修复后：

- `RetryExecutor` 先通过 `RpcExceptionMapper` 分类，只有明确 RPC/网络异常才进入重试。
- 未知本地异常直接原样抛出。
- `InterruptedException` 保留线程中断标记并立即退出。
- `DefaultRetryStrategy` 对 `HALF_OPEN_PROBE_EXHAUSTED` 允许短暂竞争场景重试，对 `ALL_INSTANCES_OPEN` 保持 fail-fast。

效果：

- 重试不会放大本地编排 bug。
- 线程中断语义正确。
- 熔断半开竞争和全开窗口有不同处理策略。

### 5.4 负载均衡状态清理

原问题：

- `RoundRobinLoadBalancer` 的服务计数器长期只增不减。
- `LeastConnectionsLoadBalancer` 的地址计数可能保留已下线地址。
- `ConsistentHashLoadBalancer` 的 hash ring 可能不随地址列表变化重建。
- 地址 key 使用 `getAddress()` 时，未解析地址可能产生空值问题。

修复后：

- round-robin 计数器增加 TTL 和容量上限清理。
- least-connections 会清理已不在当前地址快照里且活跃数为 0 的旧地址，并增加 TTL/容量上限回收。
- consistent-hash 按最新地址列表签名重建 hash ring。
- 地址 key 统一使用 `hostString:port`。

效果：

- 服务名和地址频繁扩缩容时，负载均衡器不会长期积累历史状态。
- 未解析地址场景更稳。

---

## 六、协议、SPI 和运行时全局状态优化

### 6.1 协议解码 ByteBuf 释放

原问题：

- `RpcProtocolDecoder` 在反序列化异常时可能没有释放 `ByteBuf`。

修复后：

- 解码 frame 统一放到 `finally` 中释放。

效果：

- 坏包、不兼容序列化器或反序列化异常不会造成 direct memory 泄漏。

### 6.2 SPI 扩展加载

原问题：

- `ExtensionLoader` 使用进程级 `BUILDING_INSTANCES` 判断循环依赖，可能把并发加载同一实现类不同别名误判为循环依赖。
- `loadExtensionClasses()` 先置 `initialized=true` 再加载资源，资源读取失败后可能留下部分初始化状态，后续无法重试。
- SPI 缓存缺少显式清理入口。

修复后：

- 循环依赖判断改为线程内构建集合，只检测同一创建链上的真实递归。
- 扩展类加载改成本地收集、成功后一次性提交。
- 增加 `ExtensionLoader.clearAllLoaders()` 和 `ExtensionFactory.clearCache()`。

效果：

- 并发别名加载不会误报循环依赖。
- SPI 资源读取失败后可以重新加载恢复。
- 测试和长生命周期进程可以主动清理 SPI 缓存。

### 6.3 运行时全局状态清理

原问题：

- `StatisticsManager` shutdown 后实例可能无法重启。
- `FilterRuntimeConfig` / `CircuitBreakerManager` 等全局状态在顺序启动/关闭时容易串状态。
- 直接在单个 bootstrap close 时 reset 全局状态，会影响同 JVM 仍在运行的其他实例。

修复后：

- `StatisticsManager` 改成可重启 lazy scheduler。
- `FilterRuntimeConfig` 增加 provider/consumer reset。
- `CircuitBreakerManager` 增加 `resetAll()`。
- bootstrap 关闭时使用引用计数，只有最后一个同类 bootstrap 关闭后才 reset 对应运行时状态。

效果：

- 顺序启动/关闭的状态污染减少。
- 同 JVM 多实例共存时，不会因为关闭一个实例就清掉另一个实例的运行时状态。

---

## 七、可观测性优化

### 7.1 客户端运行时指标

新增客户端指标：

- 单连接 in-flight 超限次数。
- 全局 pending request 超限次数。
- 总连接数超限次数。
- 超时扫描清理请求数。
- 重连调度次数。
- 重连成功次数。
- 重连失败次数。

核心类：

- `ClientRuntimeMetrics`
- `ClientRuntimeMetricsManager`
- `ObservabilitySnapshot`

### 7.2 统一快照入口

`ObservabilitySnapshot.capture()` 会聚合：

- 客户端运行时指标。
- `ServiceMetricsManager` 中的服务调用指标。

`StatisticsManager.printAllStatistics()` 也增加了客户端摘要输出。

### 7.3 Spring Boot 可观测入口

starter 中新增：

- `RpcObservabilityFacade`
- `RpcObservabilityEndpoint`
- `RpcObservabilityResponse`

HTTP 入口：

```http
GET /rpc/observability
```

默认行为：

- 默认只返回摘要，不返回完整 `serviceMetrics`。
- 需要详细服务指标时传 `includeServices=true`。
- 支持 `limit` 裁剪服务指标数量，内部最大上限为 200。

示例：

```http
GET /rpc/observability
GET /rpc/observability?includeServices=true&limit=50
```

效果：

- 运行中可以直接拉取 RPC 框架指标快照。
- 避免服务数量增多后 HTTP 返回体无限膨胀。

---

## 八、日志和异常语义优化

### 8.1 日志降噪

修复后：

- Netty `LoggingHandler(INFO)` 改为仅在对应类 logger 开启 DEBUG 时挂载。
- 客户端预期内异常改成 warn 摘要 + debug 详情。
- ZK session expired 从 error 调整为 warn，并区分恢复失败日志。
- 订阅失败、请求超时、客户端预算拒绝等日志语义更清楚。

效果：

- 默认线上日志不会被建连、收包、断链细节刷屏。
- 排查问题时可以通过 DEBUG 打开细粒度链路日志。

### 8.2 错误码分类

新增或收紧的异常语义：

- `CLIENT_BUSY`：客户端自身预算打满。
- `SERVER_BUSY`：服务端业务线程池过载。
- `CIRCUIT_BREAKER_OPEN[ALL_INSTANCES_OPEN]`：实例全部仍在 open 窗口。
- `CIRCUIT_BREAKER_OPEN[HALF_OPEN_PROBE_EXHAUSTED]`：half-open 探测位被并发抢完。

效果：

- 调用侧能区分客户端过载、服务端过载、网络异常和熔断状态。
- 重试策略不会把所有失败都当成同一种 server error。

---

## 九、优化后的整体项目结构

从模块看，项目仍然可以按下面几层理解：

- `rpc-core`
  - 协议、序列化、注册发现、负载均衡、熔断、重试、限流、transport、bootstrap。
- `rpc-spring`
  - Spring 注解扫描、provider 注册、consumer 引用注入、生命周期管理。
- `rpc-spring-boot-starter`
  - Spring Boot 自动配置、配置属性绑定、可观测 endpoint。
- `doc`
  - 项目理解、源码阅读、优化记录和流程说明。

从运行时角色看，可以拆成：

- provider 启动和注册。
- consumer 代理创建和调用。
- 服务发现和地址选择。
- 客户端连接池和请求管理。
- 协议编解码和网络传输。
- 服务端请求分发和业务执行。
- 响应回填和异常治理。
- 指标采集和观测输出。

---

## 十、优化后的 provider 启动流程

provider 侧的大致流程：

1. Spring Boot 自动配置创建 `RpcFrameworkConfig`。
2. `RpcSpringManager` 扫描 `@RpcService` Bean。
3. `RpcProviderBootstrap` 接收 provider 服务对象。
4. 服务对象注册到本地 `LocalRegistryImpl`。
5. provider 地址注册到注册中心：
   - local registry 场景使用本地注册表。
   - ZooKeeper 场景创建 `/rpc/{serviceName}/{host-port}` 临时节点。
6. `RpcNettyServer` 或 `RpcSocketServer` 按配置 host/port bind。
7. 服务端开始接收请求。

优化后的关键变化：

- 本地注册表是实例级，不再是静态全局表。
- 服务端真实 bind 地址和注册中心发布地址一致。
- ZooKeeper 初始连接有超时保护。
- ZooKeeper session expired 后能恢复临时节点和 watcher。
- provider bootstrap 关闭时按引用计数清理运行时状态，避免误伤同 JVM 其他 provider。

---

## 十一、优化后的 consumer 启动流程

consumer 侧的大致流程：

1. Spring Boot 自动配置创建 `RpcFrameworkConfig`。
2. `RpcSpringManager` 扫描 `@RpcReference` 字段。
3. `RpcConsumerBootstrap` 根据接口创建代理对象。
4. 代理对象注入到业务 Bean 字段。
5. 如果启用预热，`ServiceDirectory` 提前加载指定服务实例快照。
6. `RpcNettyClient` 初始化：
   - Netty eventLoop。
   - `RequestManager`。
   - `ConnectionPool`。
   - 共享超时扫描 scheduler。
   - 负载均衡器。
   - `CircuitBreakerManager`。
   - `RateLimiterManager`。
   - `RetryExecutor`。
   - `RpcClientInvocationExecutor`。

优化后的关键变化：

- 客户端连接池支持每地址多连接。
- pending 请求、单连接 in-flight、总连接数都有预算保护。
- 超时扫描和连接池空闲回收使用共享 scheduler。
- consumer bootstrap 关闭时按引用计数清理全局运行时状态。

---

## 十二、优化后的一次 RPC 调用流程

一次 consumer 到 provider 的调用，现在可以按下面的链路理解。

### 12.1 consumer 代理入口

1. 业务代码调用 `demoService.hello(...)`。
2. 调用进入 JDK 动态代理或 CGLIB 拦截器。
3. 拦截器构造 `RpcRequest`：
   - serviceName
   - methodName
   - parameterTypes
   - parameters
   - requestId
   - attachments
4. 进入 consumer filter chain。

### 12.2 consumer filter chain

filter chain 负责 consumer 入口级治理，例如：

- trace。
- MDC。
- consumer metrics。
- 服务级 circuit breaker。
- consumer 侧降级。

优化后的关键点：

- 服务级 circuit breaker 使用 executor 注入的 `CircuitBreakerManager`，和实例级 breaker 来源一致。
- 服务级 breaker 统计的是整个 cluster 调用的最终结果。
- failover 中间失败但最终成功时，服务级 breaker 记录成功，实例级 breaker 会记录中间失败实例的失败。

### 12.3 调用编排层

进入 `RpcClientInvocationExecutor` 后：

1. 解析默认配置和方法级配置。
2. 执行 consumer 限流。
3. 按 cluster 策略执行：
   - fail-fast
   - fail-over
4. 调用 `RpcServiceResolver` 解析可用地址。
5. 结合服务发现、负载均衡和实例级 breaker 选择 provider 地址。
6. 通过 `RetryExecutor` 执行重试策略。

优化后的关键点：

- 实例级 breaker 只对最终选中的实例消耗 half-open probe。
- `HALF_OPEN_PROBE_EXHAUSTED` 可以作为短暂竞争参与重试。
- `ALL_INSTANCES_OPEN` 直接 fail-fast。
- 未知本地异常不再被误包装成可重试 server error。
- `InterruptedException` 会保留中断标记并退出。

### 12.4 服务发现和地址选择

`ServiceDirectory` 获取服务实例快照：

1. 优先读取未过期缓存。
2. 缓存过期则 refresh。
3. 第一次访问会建立订阅。
4. 注册中心失败时，如果允许 stale，会回退到旧快照。

优化后的关键点：

- 同服务并发 refresh 单飞，避免打爆注册中心。
- 断线判断时通过最近地址映射缩小 refresh 范围。
- 地址映射有 TTL 和容量上限。
- 服务实例快照变化时才打印更新日志。

### 12.5 客户端发送请求

`RpcNettyClient.sendRequestToAddress(...)` 的核心步骤：

1. 从 `ConnectionPool` 获取目标地址连接。
2. 如果地址连接池没有可用连接：
   - 未达每地址连接上限则扩容。
   - 达到总连接上限则抛 `CLIENT_BUSY`。
3. 对连接尝试获取 in-flight slot。
4. slot 满则抛 `CLIENT_BUSY`。
5. 在 `RequestManager` 注册 pending request。
6. 编码 `RpcMessage`。
7. `writeAndFlush()` 发出请求。
8. 等待 future 回填或超时。

优化后的关键点：

- pending request 注册发生在连接获取之后，减少建连失败导致的无主 pending。
- `writeAndFlush()` 失败、等待超时、channel 关闭都会清理 pending request。
- 单连接、全局 pending、总连接都有硬预算。
- 预算拒绝会进入指标并明确映射为 `CLIENT_BUSY`。

### 12.6 协议编解码

请求在 Netty pipeline 中经过：

- `RpcProtocolEncoder`
- `RpcProtocolDecoder`
- heartbeat handler
- reconnect handler
- client/server handler

优化后的关键点：

- decoder 反序列化异常时也会释放 `ByteBuf`。
- Netty 协议级日志默认不刷 INFO，只有 DEBUG 开启时才挂载细粒度 `LoggingHandler`。

### 12.7 provider 接收和执行业务

服务端收到请求后：

1. `RpcRequestHandler` 接收请求。
2. 投递到业务线程池。
3. `RpcRequestExecutor` 从本地注册表找到服务对象。
4. 执行 provider filter chain：
   - provider MDC
   - provider metrics
   - provider rate limit
5. 反射调用真实服务方法。
6. 构造 `RpcResponse`。
7. 写回客户端。

优化后的关键点：

- Netty IO 线程不再等待业务执行完成。
- 业务线程池拒绝任务时返回 `SERVER_BUSY`，而不是直接断连接。
- provider metrics 会记录服务维度调用指标。

### 12.8 响应回填

客户端收到响应后：

1. `RpcClientHandler` 解析响应。
2. `RequestManager.completeResponse(response)` 根据 requestId 找到 pending future。
3. future 完成。
4. `sendRequestToAddress(...)` 返回 `RpcResponse`。
5. 上层代理将结果还原成本地方法返回值。

异常情况下：

- channel inactive：按 channel 批量失败 pending request。
- exceptionCaught：按 channel 批量失败 pending request。
- 超时扫描：清理已过 deadline 的 pending request。
- 客户端关闭：failAll 所有 pending request。

---

## 十三、优化后的关闭流程

### 13.1 客户端关闭

`RpcNettyClient.close()` 现在会：

1. 设置 closing 标记。
2. 取消请求超时扫描任务。
3. 释放共享 timeout scanner。
4. failAll 所有 pending request。
5. 关闭连接池。
6. 关闭 Netty eventLoop。
7. 关闭服务目录和服务发现。

效果：

- 关闭期间不会继续调度重连。
- 已挂起请求不会无限等待。
- 共享 scheduler 引用能释放。

### 13.2 服务端关闭

provider 关闭时会：

1. 停止 server。
2. 从注册中心注销服务地址。
3. 从本地 registry 注销服务。
4. 引用计数归零时清理 provider 侧运行时状态。

效果：

- 服务摘除后 consumer 侧能基于服务目录判断是否继续重连。
- 同 JVM 多 provider 不会互相清掉对方状态。

---

## 十四、当前运行状态判断

按当前代码和回归测试结果看，核心 RPC 功能链路已经具备较完整的稳定性保护：

- 连接池有上限和空闲回收。
- 请求有 pending 管理、超时清理和断链批量失败。
- 服务端慢业务不会阻塞 Netty IO 线程。
- 服务端过载和客户端过载有明确错误码。
- 注册中心会话过期能恢复。
- 熔断、重试和降级的统计口径更清楚。
- 长期运行状态有 TTL、容量上限或 reset 入口。
- 可观测性已有统一 snapshot 和 HTTP 读取入口。

后续不建议继续凭空改控制流。更合适的下一步是：

- 做单机基准压测。
- 做长时间稳定性压测。
- 做 provider 重启、ZK 断连、服务端线程池打满等故障压测。
- 基于压测结果调整 `maxConnectionsPerAddress`、`maxInflightRequestsPerConnection`、`maxPendingRequests`、`readTimeout`、`retryTimes` 和服务端业务线程池参数。

