# RPC 框架配置项参考手册

本文从源码中的 `RpcConfigKeys`、`RpcFrameworkConfig`、`RpcBootFrameworkProperties`、`RpcSpringBootProperties` 和默认 `rpc.properties` 整理而来，用于查询当前项目支持的配置项。

## 1. 配置来源和写法

当前项目有两种主要配置写法。

### 1.1 core properties 写法

适用于直接使用 `rpc-core` 的场景，默认配置文件是：

```text
rpc-core/src/main/resources/rpc.properties
```

示例：

```properties
rpc.transport=netty
rpc.registry.address=127.0.0.1:2181
rpc.server.port=19090
rpc.client.readTimeout=10000
```

### 1.2 Spring Boot YAML 写法

适用于使用 `rpc-spring-boot-starter` 的场景，也就是当前 `example-provider` 和 `example-consumer` 的主要方式。

示例：

```yaml
rpc:
  transport: netty
  registry:
    address: 127.0.0.1:2181
  server:
    port: 19090
  client:
    read-timeout: 10000
```

Spring Boot 支持 relaxed binding，所以 `read-timeout`、`readTimeout` 这类写法通常都能绑定。文档中优先使用 YAML 的 kebab-case 写法。

## 2. 支持的枚举和扩展名

### 2.1 transport

| 值 | 说明 |
| --- | --- |
| `netty` | 默认主实现，推荐 |
| `socket` | legacy JDK Socket 实现，不建议高并发使用 |

### 2.2 registry.type

| 值 | 说明 |
| --- | --- |
| `zookeeper` | 当前唯一注册中心实现 |

### 2.3 serializer

| 值 | 说明 |
| --- | --- |
| `protobuf` | 默认推荐 |
| `kryo` | Kryo 序列化 |
| `json` | JSON 序列化 |
| `hessian` | Hessian 序列化 |
| `java` | JDK 原生序列化 |
| `default` | SPI 别名，当前指向 protobuf |

源码里还存在 `defalut` 拼写别名，这是兼容性遗留，不建议在配置中使用。

### 2.4 loadbalancer

| 值 | 说明 |
| --- | --- |
| `random` | 默认随机 |
| `roundrobin` | 轮询 |
| `consistenthash` | 一致性哈希 |
| `leastconnections` | 最少连接 |

源码里还存在 `defalut` 拼写别名，这是兼容性遗留，不建议在配置中使用。

### 2.5 client.cluster

| 值 | 说明 |
| --- | --- |
| `failover` | 默认，失败后可换实例重试，适合幂等读场景 |
| `failfast` | 快速失败，只调用一次，适合非幂等写场景 |

解析器兼容 `fail-over`、`fail_over`、`FAILOVER` 等写法。

### 2.6 degradation.policy

| 值 | 说明 |
| --- | --- |
| `failFast` | 默认，快速失败 |
| `defaultValue` | 返回配置的默认值 |

### 2.7 circuitBreakerScope

| 值 | 说明 |
| --- | --- |
| `service` | 默认，按服务维度统计熔断 |
| `method` | 按服务方法维度统计熔断 |

### 2.8 过滤器扩展名

| 名称 | 阶段 | 说明 |
| --- | --- | --- |
| `trace` | consumer | trace 信息 |
| `mdc` | consumer | consumer MDC 日志上下文 |
| `consumerMetrics` | consumer | consumer 指标 |
| `consumerCircuitBreaker` | invoker | consumer 熔断/降级 |
| `providerRateLimit` | provider | provider 限流 |
| `providerMdc` | provider | provider MDC 日志上下文 |
| `providerMetrics` | provider | provider 指标 |

默认过滤器链：

```properties
rpc.filter.consumer=trace,mdc,consumerMetrics
rpc.filter.invoker=consumerCircuitBreaker
rpc.filter.provider=providerRateLimit,providerMdc,providerMetrics
```

## 3. 公共配置

| core properties key | Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- | --- |
| `rpc.transport` | `rpc.transport` | `netty` | 传输实现 |
| `rpc.serializer` | `rpc.serializer` | `protobuf` | 默认序列化器 |
| `rpc.loadbalancer` | `rpc.loadbalancer` | `random` | 默认负载均衡器 |

YAML 示例：

```yaml
rpc:
  transport: netty
  serializer: protobuf
  loadbalancer: random
```

## 4. 注册中心配置

| core properties key | Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- | --- |
| `rpc.registry.type` | `rpc.registry.type` | `zookeeper` | 注册中心类型 |
| `rpc.registry.address` | `rpc.registry.address` | `127.0.0.1:2181` | 注册中心地址 |
| `rpc.registry.timeout` | `rpc.registry.timeout` | core 默认 `5000`；Boot 默认 `15000` | 注册中心连接等待超时，单位毫秒 |

YAML 示例：

```yaml
rpc:
  registry:
    type: zookeeper
    address: 127.0.0.1:2181
    timeout: 15000
```

注意：

- provider 和 consumer 必须连接同一个注册中心。
- 如果 provider 注册到 ZooKeeper 的地址 consumer 无法访问，consumer 仍然会调用失败。

## 5. provider 服务端配置

| core properties key | Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- | --- |
| `rpc.server.host` | `rpc.server.host` | `127.0.0.1` | RPC 服务地址，同时影响注册到 ZooKeeper 的地址 |
| `rpc.server.port` | `rpc.server.port` | `8080` | RPC 服务端口 |
| `rpc.server.scanPackages` | `rpc.server.scan-packages` | 空 | core 注解扫描包；Spring Boot 场景通常可不配 |
| `rpc.server.autoRegisterAnnotatedServices` | `rpc.server.auto-register-annotated-services` | `true` | 是否自动注册 `@RpcService` |
| `rpc.server.bossThreads` | `rpc.server.boss-threads` | `1` | Netty boss 线程数 |
| `rpc.server.workerThreads` | `rpc.server.worker-threads` | core 默认配置文件为 `4`；Boot 默认 CPU 核数 * 2 | Netty worker 线程数 |
| `rpc.server.biz.coreThreads` | `rpc.server.biz.core-threads` | core 默认配置文件为 `4`；Boot 默认 CPU 核数 | 业务线程池核心线程数 |
| `rpc.server.biz.maxThreads` | `rpc.server.biz.max-threads` | core 默认配置文件为 `8`；Boot 默认 CPU 核数 * 2 | 业务线程池最大线程数 |
| `rpc.server.biz.queueCapacity` | `rpc.server.biz.queue-capacity` | `1000` | 业务线程池队列容量 |
| `rpc.server.shutdownTimeout` | `rpc.server.shutdown-timeout` | `10` | 优雅停机等待时间，单位秒 |
| `rpc.server.readerIdleTime` | `rpc.server.reader-idle-time` | `30000` | Netty 读空闲检测，单位毫秒 |
| `rpc.server.writerIdleTime` | `rpc.server.writer-idle-time` | `0` | Netty 写空闲检测，单位毫秒，0 表示不启用 |
| `rpc.server.allIdleTime` | `rpc.server.all-idle-time` | `0` | Netty 全空闲检测，单位毫秒，0 表示不启用 |

YAML 示例：

```yaml
rpc:
  server:
    host: 127.0.0.1
    port: 19090
    boss-threads: 1
    worker-threads: 4
    biz:
      core-threads: 4
      max-threads: 8
      queue-capacity: 1000
    shutdown-timeout: 10
    reader-idle-time: 30000
    writer-idle-time: 0
    all-idle-time: 0
```

重要说明：

- `rpc.server.host` 当前同时承担“监听地址”和“注册地址”的作用。
- 云服务器 + 本地 consumer 场景下，如果使用 SSH 隧道，provider 可以注册 `127.0.0.1:19090`。
- 如果不使用 SSH 隧道，provider 注册地址必须是 consumer 可访问的地址。

## 6. provider 限流和降级配置

| core properties key | Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- | --- |
| `rpc.server.rateLimit.enabled` | `rpc.server.rate-limit.enabled` | `false` | provider 侧限流开关 |
| `rpc.server.rateLimit.permitsPerSecond` | `rpc.server.rate-limit.permits-per-second` | core 默认配置文件为 `200`；Boot 默认 `100` | provider 每秒允许通过的请求数 |
| `rpc.server.degradation.enabled` | `rpc.server.degradation.enabled` | `false` | provider 侧降级开关 |
| `rpc.server.degradation.policy` | `rpc.server.degradation.policy` | `failFast` | provider 降级策略 |
| `rpc.server.degradation.defaultValue.{service#method}` | `rpc.server.degradation.default-value.{service#method}` | 空 | provider 默认值降级返回值 |

YAML 示例：

```yaml
rpc:
  server:
    rate-limit:
      enabled: true
      permits-per-second: 20
    degradation:
      enabled: true
      policy: defaultValue
      default-value:
        "com.rpc.HelloService#sayHello": "provider-default"
```

properties 示例：

```properties
rpc.server.rateLimit.enabled=true
rpc.server.rateLimit.permitsPerSecond=20
rpc.server.degradation.enabled=true
rpc.server.degradation.policy=defaultValue
rpc.server.degradation.defaultValue.com.rpc.HelloService#sayHello=provider-default
```

## 7. consumer 基础调用配置

| core properties key | Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- | --- |
| `rpc.client.connectTimeout` | `rpc.client.connect-timeout` | `5000` | 建立连接超时，单位毫秒 |
| `rpc.client.readTimeout` | `rpc.client.read-timeout` | `10000` | 单次 RPC 响应等待超时，单位毫秒 |
| `rpc.client.heartbeatInterval` | `rpc.client.heartbeat-interval` | `30000` | 心跳间隔，单位毫秒 |
| `rpc.client.writerIdleTime` | `rpc.client.writer-idle-time` | `30000` | 写空闲检测，单位毫秒 |
| `rpc.client.readerIdleTime` | `rpc.client.reader-idle-time` | `10000` | 读空闲检测，单位毫秒 |
| `rpc.client.retryTimes` | `rpc.client.retry-times` | `3` | 默认重试次数 |
| `rpc.client.cluster` | `rpc.client.cluster` | `failover` | 集群容错策略 |

YAML 示例：

```yaml
rpc:
  client:
    connect-timeout: 5000
    read-timeout: 10000
    heartbeat-interval: 30000
    writer-idle-time: 30000
    reader-idle-time: 10000
    retry-times: 3
    cluster: failover
```

## 8. consumer 连接池和背压配置

| core properties key | Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- | --- |
| `rpc.client.maxInflightRequestsPerConnection` | `rpc.client.max-inflight-requests-per-connection` | `256` | 单连接最大在途请求数 |
| `rpc.client.maxConnectionsPerAddress` | `rpc.client.max-connections-per-address` | `2` | 单个 provider 地址最大连接数 |
| `rpc.client.maxTotalConnections` | `rpc.client.max-total-connections` | `128` | consumer 全局最大连接数 |
| `rpc.client.maxPendingRequests` | `rpc.client.max-pending-requests` | `10000` | consumer 全局 pending 请求上限 |
| `rpc.client.idleConnectionTtlMillis` | `rpc.client.idle-connection-ttl-millis` | `60000` | 空闲连接存活时间，单位毫秒 |
| `rpc.client.idleConnectionEvictIntervalMillis` | `rpc.client.idle-connection-evict-interval-millis` | `30000` | 空闲连接清理间隔，单位毫秒 |

YAML 示例：

```yaml
rpc:
  client:
    max-inflight-requests-per-connection: 256
    max-connections-per-address: 2
    max-total-connections: 128
    max-pending-requests: 10000
    idle-connection-ttl-millis: 60000
    idle-connection-evict-interval-millis: 30000
```

## 9. consumer 重连配置

| core properties key | Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- | --- |
| `rpc.client.reconnect.enabled` | `rpc.client.reconnect.enabled` | `true` | 是否启用断线重连 |
| `rpc.client.reconnect.maxRetryTimes` | `rpc.client.reconnect.max-retry-times` | `5` | 最大重连次数 |
| `rpc.client.reconnect.initialDelaySeconds` | `rpc.client.reconnect.initial-delay-seconds` | `2` | 初始重连等待秒数 |
| `rpc.client.reconnect.maxDelaySeconds` | `rpc.client.reconnect.max-delay-seconds` | `60` | 最大重连等待秒数 |
| `rpc.client.reconnect.jitter.enabled` | `rpc.client.reconnect.jitter.enabled` | `true` | 是否启用随机抖动 |
| `rpc.client.reconnect.jitter.minSeconds` | `rpc.client.reconnect.jitter.min-seconds` | `0` | 最小抖动秒数 |
| `rpc.client.reconnect.jitter.maxSeconds` | `rpc.client.reconnect.jitter.max-seconds` | `1` | 最大抖动秒数 |

YAML 示例：

```yaml
rpc:
  client:
    reconnect:
      enabled: true
      max-retry-times: 5
      initial-delay-seconds: 2
      max-delay-seconds: 60
      jitter:
        enabled: true
        min-seconds: 0
        max-seconds: 1
```

## 10. consumer 服务发现缓存与预热配置

| core properties key | Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- | --- |
| `rpc.client.discovery.preheat.enabled` | `rpc.client.discovery.preheat.enabled` | `false` | 是否启动时预热服务目录 |
| `rpc.client.discovery.preheat.services` | `rpc.client.discovery.preheat.services` | 空 | 需要预热的服务名列表 |
| `rpc.client.discovery.cacheTtlMillis` | `rpc.client.discovery.cache-ttl-millis` | `30000` | 服务目录缓存 TTL，单位毫秒 |
| `rpc.client.discovery.allowStaleOnFailure` | `rpc.client.discovery.allow-stale-on-failure` | `true` | 注册中心失败时是否允许使用旧缓存 |

YAML 示例：

```yaml
rpc:
  client:
    discovery:
      preheat:
        enabled: true
        services:
          - com.rpc.HelloService
      cache-ttl-millis: 30000
      allow-stale-on-failure: true
```

properties 示例：

```properties
rpc.client.discovery.preheat.enabled=true
rpc.client.discovery.preheat.services=com.rpc.HelloService
rpc.client.discovery.cacheTtlMillis=30000
rpc.client.discovery.allowStaleOnFailure=true
```

## 11. consumer 限流、熔断和降级配置

| core properties key | Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- | --- |
| `rpc.client.rateLimit.enabled` | `rpc.client.rate-limit.enabled` | `false` | consumer 侧限流开关 |
| `rpc.client.rateLimit.permitsPerSecond` | `rpc.client.rate-limit.permits-per-second` | `100` | consumer 每秒允许通过的请求数 |
| `rpc.client.circuitBreaker.failureRateThreshold` | `rpc.client.circuit-breaker.failure-rate-threshold` | `50` | 熔断失败率阈值 |
| `rpc.client.circuitBreaker.minNumberOfCalls` | `rpc.client.circuit-breaker.min-number-of-calls` | `10` | 触发失败率计算的最小调用数 |
| `rpc.client.circuitBreaker.waitDurationInOpenStateMillis` | `rpc.client.circuit-breaker.wait-duration-in-open-state-millis` | `30000` | 熔断打开态等待时间，单位毫秒 |
| `rpc.client.circuitBreaker.permittedHalfOpenCalls` | `rpc.client.circuit-breaker.permitted-half-open-calls` | `5` | 半开态允许的探测请求数 |
| `rpc.client.enableDegradation` | `rpc.client.enable-degradation` | `false` | 是否启用 consumer 侧降级短路 |
| `rpc.client.degradation.policy` | `rpc.client.degradation.policy` | `failFast` | consumer 降级策略 |
| `rpc.client.degradation.defaultValue.{service#method}` | `rpc.client.degradation.default-value.{service#method}` | 空 | consumer 默认值降级返回值 |

YAML 示例：

```yaml
rpc:
  client:
    rate-limit:
      enabled: true
      permits-per-second: 100
    circuit-breaker:
      failure-rate-threshold: 50
      min-number-of-calls: 10
      wait-duration-in-open-state-millis: 30000
      permitted-half-open-calls: 5
    enable-degradation: true
    degradation:
      policy: defaultValue
      default-value:
        "com.rpc.HelloService#sayHello": "consumer-default"
```

说明：

- 熔断器会记录失败，但是否在打开后走降级短路，还依赖 `rpc.client.enableDegradation=true`。
- `failFast` 表示快速失败。
- `defaultValue` 表示从默认值映射中找 `serviceName#methodName` 对应值。

## 12. consumer 方法级覆盖配置

方法级配置用于让某个服务方法覆盖全局配置。典型场景：

- 写接口改成 `failfast`，避免重试导致重复写。
- 慢接口单独调大 `readTimeout`。
- 热点接口单独开启更严格限流。
- 某个方法按 `method` 维度熔断，避免影响同服务下其他方法。

### 12.1 core properties 写法

先声明方法别名：

```properties
rpc.client.methods=fastHello
```

再配置别名对应规则：

```properties
rpc.client.method.fastHello.service=com.rpc.HelloService
rpc.client.method.fastHello.method=sayHello
rpc.client.method.fastHello.retryTimes=0
rpc.client.method.fastHello.cluster=failfast
rpc.client.method.fastHello.readTimeout=500
rpc.client.method.fastHello.serializer=json
rpc.client.method.fastHello.loadBalancer=roundrobin
rpc.client.method.fastHello.rateLimitEnabled=true
rpc.client.method.fastHello.rateLimitPermitsPerSecond=10
rpc.client.method.fastHello.circuitBreakerScope=method
```

### 12.2 Spring Boot YAML 写法

```yaml
rpc:
  client:
    methods:
      - service-name: com.rpc.HelloService
        method-name: sayHello
        retry-times: 0
        cluster-strategy: failfast
        read-timeout: 500
        serializer-name: json
        load-balancer-name: roundrobin
        rate-limit-enabled: true
        rate-limit-permits-per-second: 10
        circuit-breaker-scope: method
```

### 12.3 方法级字段说明

| core properties 后缀 | Spring Boot YAML 字段 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `service` | `service-name` | 必填 | 服务接口名 |
| `method` | `method-name` | 必填 | 方法名 |
| `retryTimes` | `retry-times` | 继承全局 | 方法级重试次数 |
| `cluster` | `cluster-strategy` | 继承全局 | 方法级集群策略 |
| `readTimeout` | `read-timeout` | 继承全局 | 方法级读超时 |
| `serializer` | `serializer-name` | 继承全局 | 方法级序列化器 |
| `loadBalancer` | `load-balancer-name` | 继承全局 | 方法级负载均衡器 |
| `rateLimitEnabled` | `rate-limit-enabled` | 继承全局 | 方法级限流开关 |
| `rateLimitPermitsPerSecond` | `rate-limit-permits-per-second` | 继承全局 | 方法级限流 QPS |
| `circuitBreakerScope` | `circuit-breaker-scope` | `service` | 熔断统计粒度 |

## 13. 过滤器配置

| core properties key | Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- | --- |
| `rpc.filter.consumer` | `rpc.filter.consumer` | `trace,mdc,consumerMetrics` | consumer 阶段过滤器 |
| `rpc.filter.invoker` | `rpc.filter.invoker` | `consumerCircuitBreaker` | invoker 阶段过滤器 |
| `rpc.filter.provider` | `rpc.filter.provider` | `providerRateLimit,providerMdc,providerMetrics` | provider 阶段过滤器 |
| `rpc.filter.order.{filterName}` | `rpc.filter.order.{filterName}` | 使用过滤器自身 order | 覆盖过滤器排序 |

YAML 示例：

```yaml
rpc:
  filter:
    consumer:
      - trace
      - mdc
      - consumerMetrics
    invoker:
      - consumerCircuitBreaker
    provider:
      - providerRateLimit
      - providerMdc
      - providerMetrics
    order:
      providerRateLimit: 5
```

properties 示例：

```properties
rpc.filter.consumer=trace,mdc,consumerMetrics
rpc.filter.invoker=consumerCircuitBreaker
rpc.filter.provider=providerRateLimit,providerMdc,providerMetrics
rpc.filter.order.providerRateLimit=5
```

## 14. Spring Boot 接入层配置

这部分配置只属于 `rpc-spring-boot-starter`。

| Spring Boot YAML key | 默认值 | 说明 |
| --- | --- | --- |
| `rpc.spring.enabled` | `true` | 是否启用 RPC Spring Boot 自动装配 |
| `rpc.spring.scan-packages` | 空 | `@RpcService` 扫描包；为空时回退到 Spring Boot 主应用包 |

YAML 示例：

```yaml
rpc:
  spring:
    enabled: true
    scan-packages:
      - com.rpc
```

扫描包优先级：

1. `rpc.spring.scan-packages`
2. `rpc.server.scan-packages`
3. Spring Boot 主应用包

## 15. Spring Boot 完整 YAML 示例

```yaml
server:
  port: 18081

rpc:
  spring:
    enabled: true
    scan-packages:
      - com.rpc

  transport: netty
  serializer: protobuf
  loadbalancer: random

  registry:
    type: zookeeper
    address: 127.0.0.1:2181
    timeout: 15000

  server:
    host: 127.0.0.1
    port: 19090
    auto-register-annotated-services: true
    boss-threads: 1
    worker-threads: 4
    biz:
      core-threads: 4
      max-threads: 8
      queue-capacity: 1000
    rate-limit:
      enabled: false
      permits-per-second: 200
    degradation:
      enabled: false
      policy: failFast
      default-value:
        "com.rpc.HelloService#sayHello": "provider-default"
    shutdown-timeout: 10
    reader-idle-time: 30000
    writer-idle-time: 0
    all-idle-time: 0

  client:
    connect-timeout: 5000
    read-timeout: 10000
    heartbeat-interval: 30000
    writer-idle-time: 30000
    reader-idle-time: 10000
    retry-times: 3
    cluster: failover

    max-inflight-requests-per-connection: 256
    max-connections-per-address: 2
    max-total-connections: 128
    max-pending-requests: 10000
    idle-connection-ttl-millis: 60000
    idle-connection-evict-interval-millis: 30000

    reconnect:
      enabled: true
      max-retry-times: 5
      initial-delay-seconds: 2
      max-delay-seconds: 60
      jitter:
        enabled: true
        min-seconds: 0
        max-seconds: 1

    discovery:
      preheat:
        enabled: false
        services: []
      cache-ttl-millis: 30000
      allow-stale-on-failure: true

    rate-limit:
      enabled: false
      permits-per-second: 100

    circuit-breaker:
      failure-rate-threshold: 50
      min-number-of-calls: 10
      wait-duration-in-open-state-millis: 30000
      permitted-half-open-calls: 5

    enable-degradation: false
    degradation:
      policy: failFast
      default-value:
        "com.rpc.HelloService#sayHello": "consumer-default"

    methods:
      - service-name: com.rpc.HelloService
        method-name: sayHello
        retry-times: 0
        cluster-strategy: failfast
        read-timeout: 500
        serializer-name: json
        load-balancer-name: roundrobin
        rate-limit-enabled: true
        rate-limit-permits-per-second: 10
        circuit-breaker-scope: method

  filter:
    consumer:
      - trace
      - mdc
      - consumerMetrics
    invoker:
      - consumerCircuitBreaker
    provider:
      - providerRateLimit
      - providerMdc
      - providerMetrics
    order:
      providerRateLimit: 5
```

## 16. 常见配置组合

### 16.1 本地最小 provider

```yaml
server:
  port: 18080

rpc:
  registry:
    address: 127.0.0.1:2181
  server:
    host: 127.0.0.1
    port: 19090
```

### 16.2 本地最小 consumer

```yaml
server:
  port: 18081

rpc:
  registry:
    address: 127.0.0.1:2181
```

### 16.3 开启 provider 限流

```yaml
rpc:
  server:
    rate-limit:
      enabled: true
      permits-per-second: 20
```

### 16.4 开启 consumer 熔断降级

```yaml
rpc:
  client:
    circuit-breaker:
      failure-rate-threshold: 50
      min-number-of-calls: 10
      wait-duration-in-open-state-millis: 30000
      permitted-half-open-calls: 3
    enable-degradation: true
    degradation:
      policy: failFast
```

### 16.5 写接口关闭重试

```yaml
rpc:
  client:
    methods:
      - service-name: com.rpc.OrderService
        method-name: createOrder
        retry-times: 0
        cluster-strategy: failfast
```

## 17. 配置排查顺序

当配置不生效时，按下面顺序排查：

1. 确认使用的是 Spring Boot Starter 还是 core properties。
2. Spring Boot 项目优先检查 `application.yml` 和启动参数。
3. 检查属性名是否写成了错误层级，例如把 `rpc.client.*` 写到了 `rpc.server.*`。
4. 检查 provider 和 consumer 是否连接同一个 `rpc.registry.address`。
5. 检查 `rpc.server.host` 注册出来的地址 consumer 是否能访问。
6. 检查限流、熔断、降级默认是否处于关闭状态。
7. 检查方法级配置是否填写了完整的服务名和方法名。
8. 检查过滤器名称是否来自 SPI 支持列表。
