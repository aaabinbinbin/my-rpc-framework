# 云服务器 Provider + 本地 Consumer 压测阶段记录

本文用于记录本轮“云服务器运行 ZooKeeper + provider，本地运行 consumer 压测页面”的测试结果。

最终结论和面试表达已单独整理到：

```text
doc/05-testing/final-conclusion-and-interview.md
```

## 1. 测试环境

| 项目 | 配置 |
| --- | --- |
| 测试时间 | 2026-04-14 晚 |
| 云服务器 | 2C2G |
| 云服务器部署 | ZooKeeper + example-provider |
| 本地部署 | example-consumer + 压测控制台 |
| 访问方式 | SSH 隧道转发 ZooKeeper、RPC provider、provider Web 指标端口 |
| 压测页面 | `http://127.0.0.1:18081/benchmark/console` |
| provider 指标地址 | `http://127.0.0.1:18080/rpc/observability?includeServices=true&limit=200` |

说明：

- “压测任务详情”中的总调用数、成功数、失败数、QPS、P99 是当前这一轮压测任务的数据。
- 页面卡片中的 `consumer 调用数`、`provider 调用数` 是应用启动以来的累计指标，不等同于当前这一轮任务的调用数。
- 本轮测试主要用于验证项目在跨机器场景下的可运行性、基本吞吐趋势、慢接口影响和异常场景表现，不作为生产容量上限。

## 2. 结果汇总

| 轮次 | 场景 | 线程数 | 持续时间 | payload | sleep | 失败比例 | 任务总调用 | 任务失败 | QPS | P99 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 01 | 冒烟 hello | 1 | 30 秒 | 1024 | 0 ms | 0% | 439 | 0 | 14.62 | 166.441 ms |
| 02 | 单线程 hello 基线 | 1 | 120 秒 | 1024 | 0 ms | 0% | 1681 | 0 | 13.99 | 179.498 ms |
| 03 | 5 线程 hello | 5 | 120 秒 | 1024 | 0 ms | 0% | 6599 | 0 | 54.95 | 224.412 ms |
| 04 | 5 线程 payload | 5 | 120 秒 | 10240 | 0 ms | 0% | 4779 | 0 | 39.82 | 322.404 ms |
| 05 | 5 线程 sleep | 5 | 120 秒 | 1024 | 100 ms | 0% | 2314 | 0 | 19.24 | 461.497 ms |
| 06 | 5 线程 unstable | 5 | 120 秒 | 1024 | 0 ms | 10% | 5638 | 0 | 46.91 | 387.550 ms |
| 07 | 10 线程 hello | 10 | 120 秒 | 1024 | 0 ms | 0% | 11457 | 0 | 95.38 | 321.148 ms |
| 08 | 5 线程 hello 稳定性 | 5 | 600 秒 | 1024 | 0 ms | 0% | 26919 | 0 | 44.86 | 423.409 ms |
| 09 | 20 线程 hello | 20 | 120 秒 | 1024 | 0 ms | 0% | 30610 | 0 | 254.96 | 248.992 ms |
| 10 | provider 限流 20 QPS | 10 | 120 秒 | 1024 | 0 ms | 0% | 15214 | 12854 | 126.60 | 222.845 ms |
| 11 | consumer 熔断 unstable 80% | 10 | 120 秒 | 1024 | 0 ms | 80% | 2524024 | 2524024 | 21033.18 | 0.909 ms |

## 3. 初步观察

1. 单线程 30 秒和 120 秒两组 hello 场景 QPS 接近，说明基础链路能稳定运行。
2. 从 1 线程提升到 5 线程后，hello 场景 QPS 从约 14 提升到约 55，吞吐有明显提升，但 P99 也从约 179 ms 上升到约 224 ms。
3. payload 从 1KB 增加到 10KB 后，5 线程 QPS 从约 55 降到约 40，P99 从约 224 ms 上升到约 322 ms，说明请求体大小对网络传输、序列化和反序列化有可见影响。
4. sleep=100ms 的慢接口场景下，QPS 降到约 19，P99 升到约 461 ms，符合服务端处理耗时增加后吞吐下降、尾延迟上升的预期。
5. unstable=10% 场景中，压测任务详情显示任务失败数为 0，但 provider 累计失败数出现增长。这里需要后续单独排查异常是否被 consumer 侧代理层吞掉，或者压测任务是否没有把业务异常计入任务失败。
6. 10 线程 hello 场景 QPS 达到约 95，P99 约 321 ms；相比 5 线程 hello，吞吐继续上升，但尾延迟也继续升高。
7. 5 线程 10 分钟稳定性测试 QPS 约 45，P99 约 423 ms，任务失败数为 0。云服务器资源截图显示整体 CPU 和内存压力不高，这说明当前更像是跨机器链路、consumer 侧或框架请求处理开销造成的延迟波动，而不是云服务器 CPU 被打满。
8. 20 线程 hello 场景 QPS 达到约 255，任务失败数为 0，P99 约 249 ms。结合资源截图看，云服务器没有明显 CPU 或内存打满现象，说明当前小规模并发下链路还能继续承载。
9. provider 限流专项测试中，任务总调用 15214 次，失败 12854 次，失败率约 84.48%；provider 侧失败数与 consumer 侧失败数同步增长，说明 provider 侧限流已经被稳定触发。
10. consumer 熔断专项测试中，任务失败数达到 2524024，provider 端总调用只有 21 次、失败 19 次。这个现象说明熔断/降级在 consumer 侧触发后，大量请求没有继续打到 provider，而是在 consumer 侧快速失败或降级返回。该组的 21033 QPS 是 consumer 侧快速失败吞吐，不代表 provider 真实处理能力。

## 4. 截图索引

### 01 冒烟 hello

![01-before](img/01-cloud-smoke-before.png)

![01-after](img/01-cloud-smoke-after.png)

### 02 单线程 hello 基线

![02-before](img/02-cloud-single-thread-before.png)

![02-running](img/02-cloud-single-thread-running.png)

![02-after](img/02-cloud-single-thread-after.png)

### 03 5 线程 hello

![03-before](img/03-cloud-hello-5threads-before.png)

![03-running](img/03-cloud-hello-5threads-running.png)

![03-after](img/03-cloud-hello-5threads-after.png)

### 04 5 线程 payload 10KB

![04-before](img/04-cloud-payload-10kb-before.png)

![04-running](img/04-cloud-payload-10kb-running.png)

![04-after](img/04-cloud-payload-10kb-after.png)

### 05 5 线程 sleep 100ms

![05-before](img/05-cloud-sleep-100ms-before.png)

![05-running](img/05-cloud-sleep-100ms-running.png)

![05-after](img/05-cloud-sleep-100ms-after.png)

### 06 5 线程 unstable 10%

![06-before](img/06-cloud-unstable-10pct-before.png)

![06-running](img/06-cloud-unstable-10pct-running.png)

![06-after](img/06-cloud-unstable-10pct-after.png)

### 07 10 线程 hello

![07-before](img/07-cloud-hello-10threads-before.png)

![07-running](img/07-cloud-hello-10threads-running.png)

![07-after](img/07-cloud-hello-10threads-after.png)

![07-resource-top](img/07-cloud-hello-10threads-server-resource-top.png)

![07-resource-free](img/07-cloud-hello-10threads-server-resource-free-h.png)

![07-resource-docker](img/07-cloud-hello-10threads-server-resource-docker-stats.png)

### 08 5 线程 hello 10 分钟稳定性测试

![08-before](img/08-cloud-hello-5threads-10min-before.png)

![08-running](img/08-cloud-hello-5threads-10min-running-5min.png)

![08-after](img/08-cloud-hello-5threads-10min-after.png)

![08-resource-top](img/08-cloud-hello-5threads-10min-server-resource-top.png)

![08-resource-free](img/08-cloud-hello-5threads-10min-server-resource-frree-h.png)

![08-resource-docker](img/08-cloud-hello-5threads-10min-server-resource-docker-stats.png)

### 09 20 线程 hello

![09-before](img/09-cloud-hello-20threads-before.png)

![09-running](img/09-cloud-hello-20threads-running.png)

![09-after](img/09-cloud-hello-20threads-after.png)

![09-resource-top](img/09-cloud-hello-20threads-server-resource-top.png)

![09-resource-free](img/09-cloud-hello-20threads-server-resource-free-h.png)

![09-resource-docker](img/09-cloud-hello-20threads-server-resource-docker-stats.png)

### 10 provider 限流 20 QPS

![10-before](img/10-provider-ratelimit-20qps-before.png)

![10-running](img/10-provider-ratelimit-20qps-running.png)

![10-after](img/10-provider-ratelimit-20qps-after.png)

### 11 consumer 熔断 unstable 80%

![11-before](img/11-consumer-circuitbreaker-unstable80-before.png)

![11-running](img/11-consumer-circuitbreaker-unstable80-running.png)

![11-after](img/11-consumer-circuitbreaker-unstable80-after.png)

## 5. 后续结论和整理安排

### 5.1 是否继续更高并发

不建议继续在这台 2C2G 云服务器上追求更高并发。

原因：

1. 当前已经完成 `1 -> 5 -> 10 -> 20` 线程递增测试，趋势已经足够用于项目展示和面试说明。
2. 20 线程 hello 已经达到约 255 QPS，任务失败数为 0，且云服务器资源没有明显打满。
3. 如果继续升到 50、100 线程，结果很容易混入本地网络、SSH 隧道、consumer 机器、浏览器页面刷新等因素，结论反而不清晰。
4. 更真实的生产压测应该把 provider、注册中心、压测端分开部署，并接入 JVM、GC、网络和连接池指标，而不是只在 2C2G 单机上继续堆线程。

后续重点应该转向结果解释、异常统计口径确认、以及可观测能力补强。

### 5.2 异常统计口径仍需确认

第 06 组 `unstable` 场景需要重点确认：

- provider 端是否确实抛出了业务异常。
- consumer 端 RPC 代理是否把 provider 异常转换成了正常返回。
- 压测任务详情中的失败数是否应该统计业务异常。

在这个问题确认前，不建议把 `unstable` 结果作为“异常率准确性验证”的最终结论，只能作为“provider 端能够记录异常”的阶段性现象。

### 5.3 限流专项测试结论

普通压测没有测出限流，是因为默认配置里限流关闭：

```text
rpc.server.rateLimit.enabled=false
rpc.client.rateLimit.enabled=false
```

本轮 provider 限流专项测试通过下面的 provider 参数开启：

```bash
java -jar example-provider-1.0-SNAPSHOT.jar \
  --rpc.registry.address=127.0.0.1:2181 \
  --rpc.server.host=127.0.0.1 \
  --rpc.server.port=19090 \
  --server.port=18080 \
  --rpc.server.rate-limit.enabled=true \
  --rpc.server.rate-limit.permits-per-second=20
```

结果显示任务失败 12854 次，provider 失败数也达到 12854，说明 provider 侧限流能够被触发并被两端指标记录。

### 5.4 熔断专项测试结论

普通压测没有明显测出熔断，原因是熔断需要“连续失败 + 达到最小调用数 + 失败率超过阈值”这些条件。

本轮熔断专项测试中，provider 使用普通启动命令：

```bash
java -jar example-provider-1.0-SNAPSHOT.jar \
  --rpc.registry.address=127.0.0.1:2181 \
  --rpc.server.host=127.0.0.1 \
  --rpc.server.port=19090 \
  --server.port=18080
```

下面这条命令只在本地 Windows consumer 上执行，不要放到云服务器 provider 上执行。

consumer 增加了较敏感的熔断参数：

```powershell
java -jar .\example-consumer\target\example-consumer-1.0-SNAPSHOT.jar `
  --rpc.registry.address=127.0.0.1:12181 `
  --server.port=18081 `
  --rpc.client.circuit-breaker.failure-rate-threshold=50 `
  --rpc.client.circuit-breaker.min-number-of-calls=10 `
  --rpc.client.circuit-breaker.wait-duration-in-open-state-millis=30000 `
  --rpc.client.circuit-breaker.permitted-half-open-calls=3 `
  --rpc.client.enable-degradation=true `
  --rpc.client.degradation.policy=failFast
```

结果显示 provider 端只有 21 次调用，其中 19 次失败；consumer 侧产生 2524024 次失败。这个结果符合 consumer 侧熔断/降级短路的特征：少量真实请求触发熔断，后续大量请求在 consumer 侧快速失败，没有继续打到 provider。

### 5.5 已完成 10 分钟稳定性测试

本轮已经完成 5 线程 10 分钟稳定性测试：

| 参数 | 值 |
| --- | --- |
| 压测方法 | `hello` |
| 线程数 | `5` |
| 持续秒数 | `600` |
| payload 大小 | `1024` |
| sleep 毫秒 | `0` |
| 失败比例 | `0` |
| 刷新间隔 | `3 秒` |

结果已记录在第 08 组，后续不需要重复跑，除非修改了核心代码或部署方式。

### 5.6 资源截图完成情况

本轮已经为关键压测组补充了云服务器资源截图：

```text
07-cloud-hello-10threads-server-resource-top.png
07-cloud-hello-10threads-server-resource-free-h.png
07-cloud-hello-10threads-server-resource-docker-stats.png

08-cloud-hello-5threads-10min-server-resource-top.png
08-cloud-hello-5threads-10min-server-resource-frree-h.png
08-cloud-hello-5threads-10min-server-resource-docker-stats.png

09-cloud-hello-20threads-server-resource-top.png
09-cloud-hello-20threads-server-resource-free-h.png
09-cloud-hello-20threads-server-resource-docker-stats.png
```

## 6. 面试中可以这样描述

这轮测试采用了 provider 与压测端分离的方式：provider 和 ZooKeeper 部署在 2C2G 云服务器，本地运行 consumer 和压测控制台，通过 SSH 隧道访问云端服务，避免把 ZooKeeper 与 RPC 端口直接暴露到公网。

测试覆盖了基础调用、并发提升、请求体增大、慢接口、限流和熔断几个方向。从结果看，hello 场景下并发从 1、5、10 提升到 20 线程时吞吐持续提升；payload 增大和 sleep 慢接口都会降低 QPS 并推高 P99，符合 RPC 调用中网络传输、序列化和服务端处理耗时对性能的影响预期。provider 限流专项中失败数明显增长，说明限流生效；consumer 熔断专项中 provider 调用数很低但 consumer 快速失败数很高，说明熔断/降级短路发生在 consumer 侧。

需要保留的限制说明：当前服务器只有 2C2G，测试结论更适合作为项目演示和趋势分析，不应直接作为生产容量上限。后续如果要更接近真实生产压测，应把 provider、注册中心、压测端部署在独立机器上，并结合 CPU、内存、GC、网络、连接数等指标一起分析。
