# 架构地图

## 1. 总分层

```text
example-consumer
  -> rpc-spring-boot-starter / rpc-spring
  -> rpc-core consumer proxy
  -> rpc-core invocation / resilience / discovery
  -> rpc-core transport / protocol
  -> network
  -> rpc-core provider transport / dispatch / execute
  -> example-provider
```

## 2. 示例层

`example-api` 定义接口契约，consumer 和 provider 都依赖它。consumer 只知道接口，不知道 provider 的实现类。provider 持有实现类，并通过框架发布服务。

## 3. Spring 接入层

`rpc-spring` 负责把核心 RPC 能力接进 Spring 生命周期：

1. 处理 `@RpcReference` 字段注入。
2. 处理 `@RpcService` 服务发布。
3. 在 Spring 容器启动后启动 consumer/provider 运行时。
4. 在容器关闭时释放 transport、server 和注册中心资源。

`rpc-spring-boot-starter` 在 Spring Boot 场景中自动创建配置对象、`RpcSpringManager` 和可观测端点。

## 4. 核心层

`rpc-core` 是主干：

1. `api`：注解和 bootstrap。
2. `config`：配置加载和配置绑定。
3. `invoke`：代理、过滤器、调用选项、cluster。
4. `discovery` / `registry`：注册发现和本地服务映射。
5. `resilience`：熔断、限流、降级、重试。
6. `extension`：序列化、负载均衡、SPI。
7. `protocol`：消息结构和编解码。
8. `transport`：Netty client/server 和请求执行。
9. `observability`：运行指标快照。

## 5. 关键边界

| 边界 | 说明 |
| --- | --- |
| consumer 业务代码和框架 | `@RpcReference` 注入代理对象 |
| 代理和调用编排 | `RpcInvocationHandler` 把方法调用变成 `RpcRequest` |
| 调用编排和网络 | `RpcClientInvocationExecutor` 选择策略，`RpcNettyClient` 负责发送 |
| 网络和 provider 执行 | `RpcRequestDispatcher` / `RpcRequestExecutor` 进入本地调用 |
| provider 本地服务映射 | `LocalRegistryImpl` 用 serviceName 找服务对象 |

