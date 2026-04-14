# 优化后的 RPC 项目执行流文档

## 1. 当前项目是否能抗住压力面试

按当前代码和回归测试结果看，这个项目已经可以用于压力面试。这里的“抗住”不是指它已经达到生产级 RPC 框架的全部能力，而是指它具备一套能被追问的完整闭环：

- 有清晰的 consumer 到 provider 调用主链路。
- 有服务注册、服务发现、连接池、请求匹配、超时清理、断链失败、重连、限流、熔断、降级和指标链路。
- 有 Spring / Spring Boot 接入方式，能解释用户侧为什么只需要少量配置和注解。
- 有资源边界：客户端 pending 请求、单连接 in-flight、总连接数、服务端业务线程池队列都有上限。
- 有可观测性入口：service metrics、client runtime metrics 和 Spring Boot HTTP endpoint。
- 有清晰的数据语义：traceId 是业务调用级，requestId 是真实网络 attempt 级，heartbeat timestamp 是客户端发送时间，metrics 非 200 响应算失败。

面试里仍然要明确边界：这是一个自研教学/简历项目，不应该吹成成熟商用框架。更稳的说法是：项目重点是完整实现 RPC 框架主链路，并围绕稳定性、扩展性和可观测性补齐关键工程细节。

## 2. 一次 RPC 调用总览

```text
业务代码调用接口方法
-> consumer 代理对象拦截
-> 构造 RpcRequest
-> consumer filter chain
-> RpcClientInvocationExecutor 编排调用
-> consumer 限流 / 服务级熔断
-> 服务发现 ServiceDirectory
-> 负载均衡 + 实例级熔断选址
-> cluster 策略 + retry 策略
-> 连接池获取连接
-> RequestManager 注册 pending request
-> Netty 编码并发送 RpcMessage
-> provider Netty 解码
-> 心跳/业务请求分流
-> 业务请求进入 provider biz 线程池
-> provider filter chain
-> LocalRegistry 查找服务实现
-> 反射执行目标方法
-> 构造 RpcResponse
-> Netty 写回响应
-> consumer 根据 requestId 完成 pending future
-> 代理返回业务结果
```

这条链路最适合在面试开场时先讲一遍。后面所有技术细节都可以挂回这条主线。

## 3. Provider 启动执行流

Spring Boot 接入场景：

1. 用户启动 example-provider。
2. Spring Boot 加载 `rpc-spring-boot-starter` 自动配置。
3. `RpcSpringBootAutoConfiguration` 创建 `RpcFrameworkConfig`。
4. `RpcSpringManager` 扫描 `@RpcService` Bean。
5. manager 将服务接口和实现对象交给 `RpcProviderBootstrap`。
6. `RpcProviderBootstrap` 构造 provider 运行时组件。
7. `LocalRegistryImpl` 记录 `serviceName -> serviceBean`。
8. 注册中心发布 provider 地址。
9. `RpcNettyServer` 按 `RpcServerConfig` 绑定 host/port。
10. Netty pipeline 开始接收请求。

Provider 启动数据流：

```text
application.yml
-> RpcBootFrameworkProperties
-> RpcFrameworkConfig
-> RpcServerConfig
-> RpcNettyServer
-> LocalRegistryImpl / ZooKeeperRegistryImpl
```

关键点：

- `RpcFrameworkConfig` 是统一配置模型。
- `RpcServerConfig.fromFrameworkConfig(...)` 负责把框架配置转成 server 运行配置。
- 默认配置已覆盖大多数场景，example 只保留注册中心地址。
- provider 暴露地址要和真实 bind 地址一致，否则 consumer 会发现一个不可访问地址。

## 4. Consumer 启动执行流

1. 用户启动 example-consumer。
2. Spring Boot 自动装配 RPC starter。
3. `RpcSpringManager` 扫描 `@RpcReference` 字段。
4. 对每个引用接口创建代理对象。
5. 代理对象注入业务 Bean。
6. `RpcConsumerBootstrap` 创建 consumer 运行时。
7. `RpcClientConfig.fromFrameworkConfig(...)` 生成 client 配置。
8. `RpcNettyClient` 初始化 Netty、服务目录、请求管理器、连接池和调用编排器。
9. 如果配置了服务预热，提前拉取目标服务地址。
10. 用户业务代码开始调用接口方法。

Consumer 启动数据流：

```text
application.yml
-> RpcBootFrameworkProperties
-> RpcFrameworkConfig
-> RpcClientConfig
-> RpcNettyClient
-> RpcClientInvocationExecutor
-> ServiceDirectory / ConnectionPool / RequestManager
```

## 5. Consumer 调用执行流

业务代码看起来是本地调用：

```java
helloService.sayHello("Tom");
```

实际执行：

1. 调用进入代理对象。
2. `RpcInvocationHandler.invoke(...)` 拦截方法。
3. 创建新的 `RpcContext`。
4. 构造 `RpcRequest`，包含 serviceName、methodName、parameterTypes、parameters、returnType 和 attachments。
5. 构造 `FilterContext`。
6. 进入 consumer filter chain。

默认 consumer filter：

```text
trace -> mdc -> consumerMetrics
```

注意：

- `traceId` 是一次业务调用级别，用于串起 consumer 和 provider。
- `requestId` 是一次真实网络请求 attempt 级别，重试时每次 attempt 都重新生成。
- 因此 requestId 在 `RpcClientInvocationExecutor.invokeOnce(...)` 中生成，并在真实 transport attempt 期间写入 MDC。

## 6. Invoker 编排层

`RpcClientInvocationExecutor.execute(...)` 做以下事情：

1. 解析方法级配置。
2. 应用调用配置到 request attachments。
3. 执行 consumer 限流。
4. 构造 invoker filter chain。
5. 执行服务级熔断。
6. 根据 cluster 策略决定 fail-fast 或 fail-over。
7. 每次真实 attempt 进入 `invokeOnce(...)`。

服务级熔断语义：

- 统计整个服务/方法调用最终结果。
- failover 中间失败但最终成功时，服务级可以记录成功。
- 如果未选中实例就失败，例如服务发现失败，只影响服务级，不影响实例级。

实例级熔断语义：

- 只统计已经选中并真实发起过请求的实例。
- 服务发现失败、全部实例 open、半开探针耗尽这类“没真正打到实例”的情况不应该记入某个实例失败。

## 7. Netty 发送执行流

`RpcNettyClient.sendRequestToAddress(...)` 的顺序：

1. 解析 requestId。
2. 解析 readTimeout。
3. 从 `ConnectionPool` 获取连接。
4. 获取单连接 in-flight slot。
5. 注册 pending request。
6. 构造 `RpcMessage`。
7. `writeAndFlush()` 发送。
8. 等待 future 返回。
9. 成功则返回 `RpcResponse`。
10. 异常则映射为 RPC 异常并清理 pending request。
11. finally 释放单连接 in-flight slot。

为什么顺序是这样：

- 先获取连接再注册 pending，避免连接获取失败留下无效 pending。
- pending 注册后才发送，避免响应过快回到客户端时找不到 future。
- finally 释放 in-flight slot，避免异常路径泄漏连接容量。

## 8. Provider 请求执行流

`RpcRequestHandler.channelRead(...)` 收到 `RpcMessage` 后：

- 如果是 heartbeat，直接在 Netty 线程快速处理。
- 如果是业务请求，投递到 biz executor。
- 如果 biz executor 拒绝，返回 `SERVER_BUSY`。
- 如果分发过程异常，返回 `SERVER_ERROR`。

为什么心跳不进业务线程池：

- 心跳是连接保活和健康探测，不是业务请求。
- 如果业务线程池打满，心跳仍应能快速响应，否则客户端会把业务过载误判为连接异常。

默认 provider filter：

```text
providerMdc -> providerMetrics -> providerRateLimit
```

顺序解释：

- MDC 在最外层，保证后续日志都有 traceId/requestId/service/method。
- metrics 包住 rate limit，保证限流短路也能统计。
- rate limit 在业务方法前，保护 provider 业务执行资源。

## 9. 响应回填执行流

1. provider 写回 `RpcMessage(RESPONSE)`。
2. consumer `RpcClientHandler` 收到响应。
3. 解析出 `RpcResponse`。
4. `RequestManager.completeResponse(response)`。
5. 根据 response.requestId 找到 pending future。
6. future complete。
7. `RpcNettyClient.sendRequestToAddress(...)` 返回。
8. `RpcClientInvocationExecutor` 判断 code。
9. 成功则上交代理层。
10. 代理层返回 `response.data`。

失败响应处理：

- 如果 response code 不是 200，executor 映射成异常。
- 实例级熔断记录失败。
- 服务级熔断按 cluster 最终结果记录。
- consumer metrics 记录失败。

## 10. 关闭执行流

Consumer close：

```text
RpcNettyClient.close()
-> 设置 closing 标记
-> 取消 timeout scanner
-> failAll pending requests
-> 关闭连接池
-> 关闭 Netty EventLoopGroup
-> 关闭服务目录和服务发现
```

Provider shutdown：

```text
RpcNettyServer.shutdown()
-> 标记停止接收新请求
-> 关闭 server channel
-> 等待 active/inflight 请求 drain
-> 注销本地和注册中心服务
-> 关闭 boss/worker event loop
-> 关闭业务线程池
-> 关闭统计任务
```

## 11. 面试中建议这样总结执行流

```text
这个项目的一次调用从 consumer 代理开始，代理把本地接口调用转成 RpcRequest，然后经过 trace、MDC、metrics 等 consumer filter，再进入 RpcClientInvocationExecutor。executor 负责方法级配置、限流、服务级熔断、cluster 策略和 retry。每次真实网络 attempt 会通过 ServiceDirectory 做服务发现，再结合负载均衡和实例级熔断选择 provider，之后通过 ConnectionPool 获取连接，并在 RequestManager 注册 pending request。请求经过 Netty 编码发送到 provider。

provider 收到消息后先按消息类型分流，心跳走快速路径，业务请求进入业务线程池。业务线程里恢复 RpcContext，执行 provider MDC、metrics、rateLimit filter，然后从 LocalRegistry 找到服务实现并反射调用，最后构造 RpcResponse 写回。consumer 收到响应后根据 requestId 完成 pending future，代理层再把 response.data 返回给业务代码。
```
