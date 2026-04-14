# 配置、扩展与治理

## 1. 配置入口

核心配置对象：

1. `rpc-core/src/main/java/com/rpc/core/config/framework/RpcFrameworkConfig.java`
2. `rpc-core/src/main/java/com/rpc/core/config/framework/RpcConfigLoader.java`
3. `rpc-core/src/main/java/com/rpc/core/config/framework/RpcConfigKeys.java`

按职责拆分后的配置包：

| 包 | 职责 |
| --- | --- |
| `config/client` | consumer client 配置 |
| `config/server` | provider server 配置 |
| `config/framework` | 框架总配置、配置 key、加载器 |
| `config/filter` | 过滤器配置绑定 |
| `config/method` | 方法级调用配置绑定 |
| `config/source` | 配置源抽象 |

Spring Boot 配置最终会通过 `RpcBootFrameworkProperties.toFrameworkConfig()` 转成 `RpcFrameworkConfig`，再交给 core 层。

## 2. 过滤器

过滤器分三类阶段：

| 阶段 | 含义 |
| --- | --- |
| `CONSUMER` | consumer 代理入口之后、transport 调用之前 |
| `INVOKER` | consumer 调用编排内部，靠近 cluster、服务发现和真实发送 |
| `PROVIDER` | provider 本地执行前后 |

关键类：

1. `invoke/filter/api/RpcFilter.java`
2. `invoke/filter/api/FilterPhase.java`
3. `invoke/filter/runtime/FilterManager.java`
4. `invoke/filter/runtime/DefaultFilterChain.java`
5. `invoke/filter/context/FilterContext.java`

常见内置过滤器：

1. `TraceFilter`
2. `MdcFilter`
3. `ConsumerMetricsFilter`
4. `ConsumerCircuitBreakerFilter`
5. `ProviderMdcFilter`
6. `ProviderMetricsFilter`
7. `ProviderRateLimitFilter`

## 3. 治理能力位置

| 能力 | 主要位置 | 第一遍理解 |
| --- | --- | --- |
| 限流 | `resilience/ratelimit` | 请求发出或 provider 执行前先做放行判断 |
| 熔断 | `resilience/circuitbreaker` | 错误累计到阈值后短路调用 |
| 降级 | `resilience/degrade` | 失败时返回默认值或快速失败 |
| 重试 | `resilience/retry` | 调用失败后按策略再次尝试 |
| cluster | `invoke/cluster` | 决定失败时是否换实例、是否继续尝试 |
| 负载均衡 | `extension/loadbalance` | 多个 provider 实例中选一个 |
| metrics | `observability/metrics` | 记录运行态指标 |

## 4. 序列化

序列化接口：

1. `extension/serialize/Serializer.java`
2. `extension/serialize/SerializerType.java`
3. `extension/serialize/factory/SerializerFactory.java`

实现：

1. `JavaSerializer`
2. `JsonSerializer`
3. `KryoSerializer`
4. `HessianSerializer`
5. `ProtobufSerializer`

第一遍只要知道：序列化发生在协议编解码附近，用来把 `RpcRequest` / `RpcResponse` 的 body 变成字节，或从字节还原对象。

## 5. 扩展点

SPI 相关类在 `extension/spi`：

1. `SPI`
2. `Inject`
3. `Initialize`
4. `ExtensionFactory`
5. `ExtensionLoader`

第一遍不建议从 SPI 开始。先理解调用链，再回来看 SPI 如何服务于序列化、负载均衡等可替换能力。

