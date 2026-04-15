# RPC 项目压测最终结论与面试表达

本文基于 `cloud-benchmark-report.md` 中的截图和数据整理，用于项目总结、答辩和面试表达。

## 1. 测试目标

本轮测试不是简单验证项目能否启动，而是验证 RPC 框架在接近真实部署方式下的几个关键表现：

1. provider 和 consumer 分开部署后，RPC 调用链路是否稳定。
2. 并发提升时，QPS 和 P99 是否呈现合理变化。
3. 请求体变大、服务端慢处理时，吞吐和尾延迟是否符合预期。
4. provider 侧限流是否能够触发并被指标记录。
5. consumer 侧熔断/降级是否能够在高失败率场景下短路请求。
6. 2C2G 云服务器是否适合作为项目演示级压测环境。

## 2. 测试环境

| 项目 | 配置 |
| --- | --- |
| 云服务器 | 2C2G |
| 云端部署 | ZooKeeper + example-provider |
| 本地部署 | example-consumer + 压测控制台 |
| 通信方式 | 本地 consumer 通过 SSH 隧道访问云端 ZooKeeper、RPC provider、provider 指标接口 |
| 压测入口 | `http://127.0.0.1:18081/benchmark/console` |
| 指标来源 | 压测任务统计 + consumer 指标 + provider 指标 + 云服务器资源截图 |

这套部署方式比单机自测更接近真实情况，因为 provider 和压测端没有放在同一个 JVM 或同一台机器上。但它仍然不是完整生产压测，因为云服务器规格较低，且注册中心和 provider 仍在同一台机器上。

## 3. 最终结果摘要

### 3.1 基础吞吐趋势

| 场景 | 线程数 | 持续时间 | 任务总调用 | 任务失败 | QPS | P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| hello 基线 | 1 | 120 秒 | 1681 | 0 | 13.99 | 179.498 ms |
| hello 小并发 | 5 | 120 秒 | 6599 | 0 | 54.95 | 224.412 ms |
| hello 中并发 | 10 | 120 秒 | 11457 | 0 | 95.38 | 321.148 ms |
| hello 较高并发 | 20 | 120 秒 | 30610 | 0 | 254.96 | 248.992 ms |

结论：

- 并发从 1 提升到 20 线程时，QPS 持续提升，任务失败数保持为 0。
- P99 在不同轮次有波动，但整体仍处于毫秒到数百毫秒级，没有出现秒级大面积超时。
- 20 线程时云服务器资源截图没有显示 CPU 或内存被打满，因此当前测试没有触达 provider 的明确资源上限。

### 3.2 请求体和慢接口影响

| 场景 | 线程数 | 任务总调用 | 任务失败 | QPS | P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| payload 10KB | 5 | 4779 | 0 | 39.82 | 322.404 ms |
| sleep 100ms | 5 | 2314 | 0 | 19.24 | 461.497 ms |

结论：

- 请求体从 1KB 增大到 10KB 后，QPS 下降，P99 上升，说明序列化、反序列化和网络传输成本会影响 RPC 性能。
- 服务端方法主动 sleep 100ms 后，QPS 明显下降，P99 上升，说明服务端业务耗时会直接影响整体吞吐和尾延迟。

### 3.3 稳定性

| 场景 | 线程数 | 持续时间 | 任务总调用 | 任务失败 | QPS | P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| hello 稳定性 | 5 | 600 秒 | 26919 | 0 | 44.86 | 423.409 ms |

结论：

- 5 线程连续运行 10 分钟，任务失败数为 0。
- 结合云服务器 `top`、`free -h`、`docker stats` 截图，未观察到明显 CPU 打满或内存持续上涨。
- 对当前项目演示环境而言，基础链路具备一定稳定性。

### 3.4 provider 限流

provider 限流配置：

```bash
--rpc.server.rate-limit.enabled=true
--rpc.server.rate-limit.permits-per-second=20
```

| 场景 | 线程数 | 任务总调用 | 任务失败 | QPS | P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| provider 限流 20 QPS | 10 | 15214 | 12854 | 126.60 | 222.845 ms |

结论：

- provider 侧失败数和 consumer 侧失败数同步增长，说明限流结果能够在两端指标中体现。
- 任务失败率约为 84.48%，说明 10 线程压测产生的请求量明显超过 provider 限流阈值。
- 这组测试证明 provider 侧限流链路生效，而不是证明 provider 只能处理 20 QPS。

### 3.5 consumer 熔断/降级

consumer 熔断参数：

```powershell
--rpc.client.circuit-breaker.failure-rate-threshold=50
--rpc.client.circuit-breaker.min-number-of-calls=10
--rpc.client.circuit-breaker.wait-duration-in-open-state-millis=30000
--rpc.client.circuit-breaker.permitted-half-open-calls=3
--rpc.client.enable-degradation=true
--rpc.client.degradation.policy=failFast
```

| 场景 | 线程数 | 任务总调用 | 任务失败 | QPS | P99 | provider 调用 | provider 失败 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| unstable 80% | 10 | 2524024 | 2524024 | 21033.18 | 0.909 ms | 21 | 19 |

结论：

- provider 端只有 21 次调用，consumer 侧产生 2524024 次失败。
- 这说明少量真实失败触发熔断后，大量请求在 consumer 侧被快速失败或降级短路，没有继续打到 provider。
- 这组的 `21033.18 QPS` 是 consumer 快速失败吞吐，不代表 provider 真实处理能力。
- 这组测试可以证明 consumer 侧熔断/降级路径生效，但不能用来说明 provider 能处理 2 万 QPS。

## 4. 最终结论

### 4.1 可以确认的能力

1. RPC 基础链路可稳定运行  
   provider 部署在云服务器，本地 consumer 通过注册中心发现服务并完成跨机器 RPC 调用，基础链路可用。

2. 并发提升时吞吐有明显增长  
   hello 场景从 1 线程到 20 线程，QPS 从约 14 提升到约 255，且任务失败数为 0。

3. 请求体大小和服务端耗时会影响性能  
   payload 10KB 和 sleep 100ms 场景都导致 QPS 下降、P99 上升，符合 RPC 性能模型。

4. 10 分钟稳定性测试无任务失败  
   5 线程运行 10 分钟，任务失败数为 0，云服务器资源未出现明显异常。

5. provider 限流可触发  
   开启 provider 侧 20 QPS 限流后，失败数明显增长，说明 provider 限流过滤器生效。

6. consumer 熔断/降级可触发  
   unstable 80% 场景下 provider 调用很少，但 consumer 快速失败大量增长，说明熔断/降级短路发生在 consumer 侧。

### 4.2 不能夸大的地方

1. 不能说项目已经达到生产级压测结论  
   当前云服务器只有 2C2G，且 ZooKeeper 和 provider 在同一台机器上。

2. 不能把 consumer 熔断测试里的 21033 QPS 当成 provider QPS  
   这是 consumer 侧快速失败吞吐，不是 provider 真实处理吞吐。

3. 不能说已经找到系统最大承载能力  
   20 线程测试没有打满 CPU、内存或连接资源，只能说明当前压力下稳定。

4. 不能只看页面 QPS 判断性能  
   更严格的压测还需要结合 JVM GC、线程池、连接池、网络、CPU、内存和注册中心指标。

## 5. 面试表达

### 5.1 一分钟版本

我对这个 RPC 项目做了一轮跨机器压测。部署方式是把 ZooKeeper 和 provider 放到 2C2G 云服务器，本地运行 consumer 和压测控制台，通过 SSH 隧道访问云端服务，这样避免把 ZooKeeper 和 RPC 端口直接暴露到公网。

测试覆盖了基础调用、并发递增、payload 增大、慢接口、10 分钟稳定性、provider 限流和 consumer 熔断。结果上，hello 场景从 1 线程到 20 线程，QPS 从约 14 提升到约 255，任务失败数为 0；payload 变大和服务端 sleep 后，QPS 下降、P99 上升，符合预期；provider 限流开启后失败数明显增长；consumer 熔断场景下 provider 调用数很少，但 consumer 侧快速失败大量增加，说明熔断短路生效。

我不会把这轮测试说成生产容量结论，因为机器只有 2C2G，而且注册中心和 provider 还在同一台服务器上。它更适合作为项目稳定性和治理能力验证。真正生产压测还需要拆分机器，并接入 GC、线程池、连接池、网络和系统资源指标。

### 5.2 面试官问“你怎么证明这个 RPC 框架稳定”

可以这样回答：

我不是只跑了一个 hello demo，而是把 provider 和 consumer 分开部署做了多组对照测试。基础链路上，我先跑 1 线程和 5 线程确认调用稳定，再提升到 10、20 线程观察吞吐和 P99 变化。20 线程下 120 秒内完成 30610 次调用，任务失败数为 0，QPS 约 255。

稳定性方面，我做了 5 线程 10 分钟测试，任务失败数为 0，并且同时截取了云服务器 CPU、内存和 Docker 资源状态，确认没有明显资源打满或内存异常上涨。

治理能力方面，我单独做了 provider 限流和 consumer 熔断测试。provider 限流设置为 20 QPS 后，失败数明显增长，说明限流过滤器生效；consumer 熔断测试里，provider 只收到少量请求，但 consumer 侧出现大量快速失败，说明熔断后请求被短路，没有继续打到 provider。

### 5.3 面试官问“你的测试有什么不足”

可以这样回答：

这轮测试主要是项目演示级和趋势验证，还不是完整生产压测。限制主要有三个：

1. 云服务器只有 2C2G，不能代表生产规格。
2. ZooKeeper 和 provider 部署在同一台服务器，没有完全隔离注册中心和服务端资源。
3. 当前主要依赖页面指标和系统截图，还没有接入完整的 JVM GC、线程池、连接池、网络包、连接数和火焰图分析。

如果继续完善，我会把 provider、ZooKeeper、压测端分到不同机器，使用 JMeter 或自定义 benchmark 客户端做稳定压力，并接入 Prometheus、Grafana、JVM GC 日志和 Netty 连接池指标，这样才能更准确判断系统瓶颈。

### 5.4 面试官问“限流和熔断分别怎么测的”

可以这样回答：

限流我测的是 provider 侧限流。默认限流是关闭的，所以我重启 provider 时显式开启：

```bash
--rpc.server.rate-limit.enabled=true
--rpc.server.rate-limit.permits-per-second=20
```

然后用 10 线程持续压 hello 接口。结果是任务总调用 15214 次，失败 12854 次，provider 和 consumer 的失败数都增长，说明请求到达 provider 后被限流过滤器拦截，并且错误能被 consumer 侧感知。

熔断我测的是 consumer 侧熔断。我把 unstable 方法的失败比例设置成 80%，并把 consumer 熔断阈值设置得比较敏感，比如最小调用数 10、失败率阈值 50%。测试结果是 provider 端只收到 21 次调用，其中 19 次失败，但 consumer 侧产生了 2524024 次快速失败。这说明前面的少量失败把熔断器打开了，后续请求在 consumer 侧被短路，没有继续打到 provider。

### 5.5 面试官问“为什么熔断 QPS 那么高”

可以这样回答：

熔断测试里的 21033 QPS 不是 provider 处理能力，而是 consumer 侧快速失败能力。熔断打开后，请求不再走完整网络 RPC 链路，而是在 consumer 本地快速失败或降级返回，所以延迟非常低、QPS 很高。这个指标只能说明熔断短路生效，不能拿来宣传 provider 能处理 2 万 QPS。

### 5.6 简历或项目描述可以这样写

可以写：

```text
对自研 RPC 框架完成跨机器压测与治理能力验证：将 provider 与 ZooKeeper 部署在 2C2G 云服务器，本地运行 consumer 压测控制台，通过 SSH 隧道完成跨机器调用；设计并执行 1/5/10/20 线程递增压测、10 分钟稳定性测试、payload 放大、慢接口、provider 限流和 consumer 熔断场景。20 线程 hello 场景下 120 秒完成 30610 次调用、任务失败数为 0；provider 限流和 consumer 熔断专项测试均能触发并被两端指标观测。
```

如果想更稳妥，可以写短一点：

```text
为自研 RPC 框架补充压测与可观测验证，覆盖跨机器调用、并发递增、稳定性、限流和熔断场景，并基于 consumer/provider 双端指标和服务器资源截图整理测试报告。
```

## 6. 后续优化方向

1. 暴露更完整的熔断器状态指标，例如 CLOSED、OPEN、HALF_OPEN。
2. 在压测页面中区分业务成功、业务失败、限流失败、熔断失败、超时失败。
3. 接入 JVM GC、线程池、Netty 连接池、pending request、重试次数等运行时指标。
4. 增加多 provider 部署，验证负载均衡、实例级熔断和故障切换。
5. 使用独立压测机器，避免本地 consumer、浏览器页面和网络隧道影响压测结论。
