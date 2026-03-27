# RPC 统一接口抽象

## 目标

在已经完成客户端和服务端瘦身分层的基础上，再向上抽一层统一接口，避免上层代码直接依赖 Netty 具体实现。

## 新增接口

### `RpcTransport`

路径：

- [RpcTransport.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/RpcTransport.java)

职责：

- 统一客户端请求发送接口
- 屏蔽底层是 Netty 还是未来的 Socket 实现

当前实现：

- [RpcNettyClient.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/client/RpcNettyClient.java)

当前接入方：

- [RpcProxyFactory.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/proxy/RpcProxyFactory.java)
- [RpcInvocationHandler.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/proxy/impl/RpcInvocationHandler.java)
- [RpcMethodInterceptor.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/proxy/impl/RpcMethodInterceptor.java)
- [AsyncInvocationHandler.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/async/AsyncInvocationHandler.java)

### `RpcRequestProcessor`

路径：

- [RpcRequestProcessor.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/server/RpcRequestProcessor.java)

职责：

- 统一服务端请求处理接口
- 屏蔽底层处理器具体是“Netty 分发器”还是未来的其他协议适配器

当前实现：

- [RpcRequestDispatcher.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/server/dispatch/RpcRequestDispatcher.java)

当前接入方：

- [RpcRequestHandler.java](/D:/aaaRPC/my-rpc-framework/rpc-core/src/main/java/com/rpc/transport/netty/server/handler/RpcRequestHandler.java)

## 为什么这样抽

- 上层代理层不再依赖 `RpcNettyClient`
- 服务端 Handler 不再依赖 `RpcRequestDispatcher`
- Netty 现在只是默认实现，而不是系统边界本身

## 结果

重构后可以形成两条稳定抽象：

- 客户端：`Proxy/Async -> RpcTransport -> RpcNettyClient`
- 服务端：`RpcRequestHandler -> RpcRequestProcessor -> RpcRequestDispatcher`

后续如果补 `socket` 版客户端或服务端处理链，可以直接实现这两个接口，而不必继续修改上层调用方。
