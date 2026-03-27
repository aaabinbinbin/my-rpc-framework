# RPC 服务端瘦身重构方案

## 目标

把 `RpcNettyServer` 中的请求处理职责继续下沉，让服务端拆成三层：

- `RpcNettyServer`：只负责 Netty 启动、pipeline 装配、生命周期管理
- `RpcRequestHandler`：只负责 Netty 入站适配
- `RpcRequestDispatcher` / `RpcRequestExecutor`：负责请求分发和 RPC 执行

## 现状问题

当前服务端虽然比客户端轻，但职责还是混在一起：

- [RpcNettyServer.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/server/RpcNettyServer.java) 既负责启动，也直接决定业务处理器实例
- [RpcRequestHandler.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/server/handler/RpcRequestHandler.java) 同时负责
  - 消息类型分发
  - 心跳处理
  - 本地服务查找
  - 反射执行
  - 统计记录
  - 响应构建与发送

这样后续如果要接入鉴权、限流、线程池隔离、服务级过滤器，`RpcRequestHandler` 会继续膨胀。

## 重构原则

- 不改服务暴露接口，不动 `LocalRegistry`
- 不改协议对象和编解码链路
- `RpcNettyServer.start()` 入口保留
- 优先抽离执行逻辑，而不是重写服务端流程

## 新增类

### 1. `RpcRequestExecutor`

路径：

- [RpcRequestExecutor.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/server/dispatch/RpcRequestExecutor.java)

职责：

- 从 `LocalRegistry` 获取服务实例
- 通过反射执行目标方法
- 记录服务统计
- 产出 `RpcResponse`

### 2. `RpcRequestDispatcher`

路径：

- [RpcRequestDispatcher.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/server/dispatch/RpcRequestDispatcher.java)

职责：

- 根据消息类型分发请求
- 处理心跳请求
- 调用 `RpcRequestExecutor`
- 构建并发送响应消息

## 调整后的职责边界

### `RpcNettyServer`

保留：

- 事件循环组创建和关闭
- ServerBootstrap 配置
- pipeline 初始化
- 服务端生命周期管理

移出：

- 不再承载请求执行逻辑

### `RpcRequestHandler`

保留：

- `RpcMessage` 类型检查
- 把消息委托给 `RpcRequestDispatcher`

移出：

- 心跳处理
- 业务请求分发
- 本地服务执行
- 统计记录
- 响应构建发送

## 为什么这是“尽量少改 RPC 代码”

- 服务注册、服务暴露和启动入口都不变
- 现有 Netty pipeline 顺序不变
- `RpcRequestHandler` 仍然在原位置，只是变成薄适配层
- 原有业务执行代码基本是原样迁移到 `RpcRequestExecutor`

## 结果

重构后服务端会形成更清晰的分层：

- `RpcNettyServer`：启动层
- `RpcRequestHandler`：Netty 适配层
- `RpcRequestDispatcher`：分发层
- `RpcRequestExecutor`：执行层

这样后续如果增加线程池隔离、服务过滤器或统一异常映射，可以继续挂在分发层或执行层，而不是回填到 Netty 启动类里。
