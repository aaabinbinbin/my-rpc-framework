# 一次 RPC 调用链路

## 1. 主线

```text
ExampleConsumerApplication
  -> @RpcReference 注入代理
  -> helloService.sayHello(...)
  -> RpcInvocationHandler.invoke(...)
  -> RpcRequest
  -> consumer filter chain
  -> RpcClientInvocationExecutor
  -> rate limit / invocation options / invoker filter
  -> cluster / retry / load balance / service discovery
  -> RpcNettyClient
  -> RpcProtocolEncoder
  -> network
  -> RpcProtocolDecoder
  -> RpcRequestHandler
  -> RpcRequestDispatcher
  -> RpcRequestExecutor
  -> provider filter chain
  -> LocalRegistryImpl
  -> HelloServiceImpl.sayHello(...)
  -> RpcResponse
  -> requestId 对应 future 回填
  -> consumer 业务代码拿到返回值
```

## 2. Consumer 侧关键点

1. `RpcSpringManager` 或 `RpcConsumerBootstrap` 负责创建 consumer 运行时。
2. `RpcProxyFactory` 创建接口代理。
3. `RpcInvocationHandler` 接住业务方法调用。
4. `RpcRequest` 保存 serviceName、methodName、parameterTypes、parameters、returnType。
5. `DefaultFilterChain` 执行 consumer 过滤器。
6. `RpcClientInvocationExecutor` 负责调用编排，不直接承担底层网络细节。
7. `RpcServiceResolver` 通过服务目录和负载均衡选择 provider 地址。
8. `RpcTransportInvoker` 把“向某个地址发请求”的动作交给 transport。

## 3. Provider 侧关键点

1. `RpcProviderBootstrap` 创建 provider server 和注册中心。
2. `RpcNettyServer` 接收网络请求。
3. `RpcRequestHandler` 识别请求消息。
4. `RpcRequestDispatcher` 把业务请求交给业务线程池和执行器。
5. `RpcRequestExecutor` 恢复 `RpcContext`，运行 provider 过滤器。
6. `LocalRegistryImpl` 用 serviceName 找到本地服务对象。
7. `invokeTarget(...)` 通过反射执行真实方法。
8. 执行结果统一封装为 `RpcResponse`。

## 4. 两个 ID

| ID | 作用 |
| --- | --- |
| `requestId` | 对应一次真实出网请求，用于响应回填和日志定位 |
| `traceId` | 串起同一次业务调用的多段链路，跨重试保持一致更有价值 |

## 5. 第一遍要记住的判断

`RpcInvocationHandler` 不是直接做网络细节；它负责把本地方法调用翻译成 RPC 请求，并把请求交给后续调用链。

`RpcClientInvocationExecutor` 不是 Netty client；它负责把限流、熔断、重试、cluster、服务发现和负载均衡组织好。

`RpcRequestExecutor` 不是网络 handler；它负责 provider 进程内的真实业务执行。

