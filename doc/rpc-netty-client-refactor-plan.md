# RPC 客户端瘦身重构方案

## 目标

把降级、熔断、重试和服务实例选择从 `RpcNettyClient` 中抽离出来，让 `RpcNettyClient` 更接近“只负责构建消息和发送消息”的传输层组件，同时尽量少改现有调用入口。

## 现状问题

当前 [RpcNettyClient.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/client/RpcNettyClient.java) 同时承担了这些职责：

- 服务发现
- 负载均衡
- 重试编排
- 服务级熔断失败记录
- 实例级熔断成功记录
- 降级决策与执行
- 连续失败计数
- 请求消息构建与发送
- 同步等待响应

这导致 `RpcNettyClient` 同时是传输层、容错层和服务寻址层，职责过重，后续想扩展限流、路由、灰度时会继续堆在同一个类里。

## 重构原则

- 保留 `sendRequest(RpcRequest)` 这个现有公开入口，避免影响代理层和异步调用层
- 不改协议对象，不改编码器/解码器，不改现有 Netty pipeline
- 把可独立测试的逻辑拆到独立类，减少 `RpcNettyClient` 体积
- 优先移动逻辑，不重写业务流程

## 新增类

### 1. `RpcServiceResolver`

路径：

- [RpcServiceResolver.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/client/invocation/RpcServiceResolver.java)

职责：

- 根据 `serviceName` 从注册中心查实例
- 调用负载均衡器选出目标实例
- 在实例选择阶段复用已有熔断过滤能力

迁移来源：

- `RpcNettyClient.sendRequestAsync(...)` 中的注册中心查询和实例选择逻辑
- `RpcNettyClient.doSendRequestWithInstanceCircuitBreaker(...)` 中的注册中心查询和实例选择逻辑

### 2. `RpcClientInvocationExecutor`

路径：

- [RpcClientInvocationExecutor.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/client/invocation/RpcClientInvocationExecutor.java)

职责：

- 调用前降级判断
- 使用已有 `RetryExecutor` 做重试
- 调用失败后记录服务级熔断失败
- 调用成功后记录实例级熔断成功
- 管理连续失败计数

迁移来源：

- `RpcNettyClient.sendRequest(...)` 中的降级、重试、异常处理逻辑
- `RpcNettyClient.shouldDegrade(...)`
- `RpcNettyClient.resetFailureCount(...)`

### 3. `RpcTransportInvoker`

路径：

- [RpcTransportInvoker.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/client/invocation/RpcTransportInvoker.java)

职责：

- 作为传输层回调接口
- 让调用编排层不直接依赖 `Netty` 细节

## `RpcNettyClient` 保留的职责

重构后 `RpcNettyClient` 主要保留：

- 初始化 Netty 客户端和 pipeline
- 管理连接池
- 生成请求 ID
- 构建 `RpcHeader` / `RpcMessage`
- 写出消息并等待响应
- 提供异步发送能力

## 主要方法迁移

### 保留在 `RpcNettyClient`

- `sendRequest(RpcRequest)`：保留入口，但内部改为委托给 `RpcClientInvocationExecutor`
- `sendRequestAsync(RpcRequest, long)`：保留异步入口，但实例解析改为委托给 `RpcServiceResolver`
- `sendRequestToAddress(RpcRequest, InetSocketAddress)`：新增，作为真正的同步传输实现
- `sendRequestAsyncToAddress(RpcRequest, long, InetSocketAddress)`：新增，作为真正的异步传输实现

### 从 `RpcNettyClient` 移出

- `shouldDegrade(...)`
- `resetFailureCount(...)`
- `sendRequest(...)` 内部的重试、熔断、降级编排
- `doSendRequestWithInstanceCircuitBreaker(...)` 中的实例解析和实例熔断记录

## 为什么这是“尽量少改 RPC 代码”

- 代理层调用方式不变，`RpcInvocationHandler`、`RpcMethodInterceptor` 和异步调用层继续调用 `sendRequest(...)`
- 协议对象、注册中心接口、负载均衡接口、重试器和熔断器都直接复用
- Netty handler、连接池、请求管理器都不动
- 主要是把既有逻辑移动到两个新增类里，控制改动范围集中在客户端

## 结果

这次重构之后，客户端职责边界会更清楚：

- `RpcNettyClient`：传输
- `RpcServiceResolver`：服务寻址
- `RpcClientInvocationExecutor`：容错编排

这样后续再接入限流、灰度、打标路由时，也可以继续沿着“编排层扩展，传输层保持稳定”的方向演进。
