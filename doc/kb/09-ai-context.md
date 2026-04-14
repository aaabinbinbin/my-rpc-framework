# AI / 检索上下文

这份文件用于给 AI 助手、IDE 检索或人工快速索引提供稳定上下文。它只保留高密度事实，不承担教程职责。

## 1. Project Context

This repository is a Maven multi-module Java RPC framework.

Recommended runtime/build JDK: JDK 16 or newer. The root POM still has Java 11 properties, but `rpc-core` and `rpc-spring-boot-starter` currently set compiler source/target to 16.

Modules:

- `rpc-core`: core RPC engine, including annotations, bootstrap, proxy, config, discovery, registry, protocol, transport, filters, resilience, extensions and metrics.
- `rpc-spring`: Spring lifecycle integration for `@RpcReference` and `@RpcService`.
- `rpc-spring-boot-starter`: Spring Boot auto-configuration, property binding and observability endpoint.
- `example-api`: shared service contract.
- `example-provider`: demo provider application.
- `example-consumer`: demo consumer application.

## 2. Main Flow

```text
ExampleConsumerApplication
-> @RpcReference proxy
-> RpcInvocationHandler
-> RpcRequest
-> consumer filters
-> RpcClientInvocationExecutor
-> service discovery / load balancing / cluster / retry / circuit breaker
-> RpcNettyClient
-> RpcProtocolEncoder
-> network
-> RpcProtocolDecoder
-> RpcRequestHandler
-> RpcRequestDispatcher
-> RpcRequestExecutor
-> provider filters
-> LocalRegistryImpl
-> HelloServiceImpl
-> RpcResponse
-> RequestManager future completion
```

## 3. Important Files

Start here:

- `README.md`
- `doc/README.md`
- `doc/kb/00-index.md`
- `doc/01-start-here/05-current-implementation-snapshot.md`
- `doc/02-main-story/01-one-rpc-call-overview.md`
- `doc/03-source-reading/01-reading-order.md`

Core code:

- `rpc-core/src/main/java/com/rpc/core/api/bootstrap/RpcConsumerBootstrap.java`
- `rpc-core/src/main/java/com/rpc/core/api/bootstrap/RpcProviderBootstrap.java`
- `rpc-core/src/main/java/com/rpc/core/invoke/proxy/impl/RpcInvocationHandler.java`
- `rpc-core/src/main/java/com/rpc/core/transport/netty/client/invocation/RpcClientInvocationExecutor.java`
- `rpc-core/src/main/java/com/rpc/core/transport/netty/client/RpcNettyClient.java`
- `rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestDispatcher.java`
- `rpc-core/src/main/java/com/rpc/core/transport/netty/server/dispatch/RpcRequestExecutor.java`
- `rpc-spring/src/main/java/com/rpc/spring/RpcSpringManager.java`
- `rpc-spring-boot-starter/src/main/java/com/rpc/spring/boot/RpcSpringBootAutoConfiguration.java`

## 4. Ignore

Ignore these for project understanding:

- `target/`
- `.idea/`
- `.git/`
- compiled `.class` files
- surefire dump/report history unless debugging tests
- `rpc-core/src/main/java/com/rpc/core/transport/socket/legacy` on first-pass learning

