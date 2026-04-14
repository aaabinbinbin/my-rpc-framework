# Spring 与 Spring Boot 接入

## 1. 两条入口

| 场景 | 入口 |
| --- | --- |
| 普通 Spring | `@EnableRpc` + `RpcSpringRegistrar` |
| Spring Boot | `RpcSpringBootAutoConfiguration` |

Spring Boot 示例应用走自动装配路径，不需要显式写 `@EnableRpc`。

## 2. `@RpcReference`

`@RpcReference` 用在 consumer 侧字段上。

处理主线：

```text
Spring 创建 consumer bean
  -> RpcSpringManager 在生命周期中检查字段
  -> 为接口创建 RPC 代理
  -> 把代理对象注入字段
  -> 业务代码调用字段方法
  -> 实际进入 RpcInvocationHandler
```

要点：注入进去的不是 `HelloServiceImpl`，而是一个代理对象。

## 3. `@RpcService`

`@RpcService` 用在 provider 服务实现类上。

处理主线：

```text
Spring 扫描服务实现类
  -> 服务实现类成为 Spring Bean
  -> RpcSpringManager 收集服务 bean
  -> 注册到 provider 本地注册表
  -> 启动 RPC server
  -> 向注册中心注册服务地址
```

要点：Spring 场景里应复用容器里的服务对象，而不是绕过 Spring 反射创建一个新对象。

## 4. Boot 自动装配做了什么

`RpcSpringBootAutoConfiguration` 主要做四件事：

1. 绑定配置，创建 `RpcFrameworkConfig`。
2. 创建 `RpcSpringManager`。
3. 注册 `RpcObservabilityFacade` 和 Web 场景下的 `RpcObservabilityEndpoint`。
4. 提前扫描 `@RpcService`，把服务实现类注册为 Spring BeanDefinition。

## 5. 配置入口

示例配置在：

1. `example-provider/src/main/resources/application.yml`
2. `example-consumer/src/main/resources/application.yml`

当前示例使用：

```yaml
rpc:
  registry:
    address: ${RPC_REGISTRY_ADDRESS:8.134.204.101:2181}
```

本地调试时建议通过环境变量覆盖：

```text
RPC_REGISTRY_ADDRESS=127.0.0.1:2181
```

