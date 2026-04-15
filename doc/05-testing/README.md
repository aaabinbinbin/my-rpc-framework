# RPC 生产可用性与压测手册

这份文档用于验证当前 RPC 项目在“接近生产环境”的压力、稳定性、故障恢复和资源使用表现。它不是普通功能测试手册，重点不是证明项目能跑起来，而是回答这些问题：

1. 单 provider 能承受多少 QPS。
2. 多并发下平均延迟、P95、P99 是否可接受。
3. 长时间运行是否有内存、线程、连接、pending request 泄漏。
4. provider 重启、ZooKeeper 抖动、连接中断时 consumer 表现是否符合预期。
5. 限流、熔断、重试、负载均衡、连接池是否真的按配置工作。
6. 面试中被问“你怎么证明这个 RPC 框架稳定”时，能拿出哪些测试数据。

测试结果统一记录到：

```text
doc/05-testing/results/
```

结果模板：

```text
doc/05-testing/results/RESULT_TEMPLATE.md
```

云服务器 provider + 本地 consumer 压测步骤：

```text
doc/05-testing/cloud-provider-local-consumer.md
```

云服务器 provider + 本地 consumer 阶段性压测结果：

```text
doc/05-testing/cloud-benchmark-report.md
```

最终测试结论与面试表达：

```text
doc/05-testing/final-conclusion-and-interview.md
```

## 1. 重要前提

### 1.1 当前 HTTP 接口测到的是 HTTP + RPC 链路

当前项目已经给示例应用接入 `spring-boot-starter-web`，可以打开可观测页面查看当前 JVM 内的 RPC 指标。

但要注意：`example-consumer` 的业务调用仍然来自 `ApplicationRunner`：

```java
helloService.sayHello("consumer");
helloService.add(1, 2);
```

Dashboard 只是展示指标，不是压测入口。因此仍然不能直接用 JMeter 的 HTTP Request Sampler 压 `example-consumer` 来代表 RPC 性能。

你有三种压测路线：

| 路线 | 是否改项目 | 适用场景 | 建议 |
| --- | --- | --- | --- |
| 自定义 RPC 压测客户端 | 已内置 `example-benchmark` | 最贴近 RPC 本身，绕过 HTTP | 推荐 |
| JMeter Java Request / JSR223 | 需要把 RPC client 依赖放进 JMeter classpath 或写脚本 | 想用 JMeter 统计吞吐和延迟 | 推荐 |
| 给 consumer 临时加 HTTP wrapper | 需要新增 Web 依赖和 Controller | 想用 JMeter HTTP Sampler 快速压 | 可用，但测到的是 HTTP + RPC 混合链路 |

后续步骤会同时给出“推荐方式”和“JMeter 方式”。

当前项目已经内置了一组测试 HTTP 接口，便于 JMeter 发起请求。这些接口分两类：

1. provider 本地基线接口：只测 provider Web + 本地方法，不经过 RPC。
2. consumer RPC 压测接口：JMeter 调 consumer HTTP 接口，consumer 内部再调用 provider RPC。

这意味着 JMeter HTTP Sampler 测到的链路是：

```text
JMeter -> consumer HTTP Controller -> RPC client -> provider RPC server -> provider service
```

如果你要估算“纯 RPC”性能，优先使用 `example-benchmark`；如果你要验证“接入 Web 应用后的整体链路”，这些 HTTP 接口可以直接使用。

### 1.2 可观测页面

压测控制台页面：

```text
http://127.0.0.1:18081/benchmark/console
```

这个页面运行在 consumer 进程中，可以直接开始/停止 RPC 压测，并同时展示：

1. 压测任务自身统计：QPS、成功数、失败数、平均耗时、P95、P99、最大耗时。
2. consumer 端 `/rpc/observability` 指标。
3. provider 端 `/rpc/observability` 指标。

页面默认通过 consumer 后端代理读取 provider 指标，默认地址是：

```text
http://127.0.0.1:18080/rpc/observability?includeServices=true&limit=200
```

如果 provider 不在本机默认端口，可以在页面中的“provider 指标地址”输入框修改。当前代理只允许访问 `127.0.0.1` 或 `localhost`，避免这个测试入口被误用为任意 URL 代理。

provider 启动后打开：

```text
http://127.0.0.1:18080/rpc/observability/dashboard
```

consumer 启动后打开：

```text
http://127.0.0.1:18081/rpc/observability/dashboard
```

JSON 原始数据：

```text
http://127.0.0.1:18080/rpc/observability?includeServices=true&limit=200
http://127.0.0.1:18081/rpc/observability?includeServices=true&limit=200
```

注意：页面展示的是“当前进程”的内存指标。provider 页面看 provider 进程，consumer 页面看 consumer 进程。

### 1.3 测试接口清单

provider 本地基线接口：

| 接口 | 方法 | 用途 |
| --- | --- | --- |
| `/benchmark/provider/health` | GET | provider 进程健康检查 |
| `/benchmark/provider/direct/hello?name=test` | GET | provider 本地 hello 基线 |
| `/benchmark/provider/direct/add?a=1&b=2` | GET | provider 本地计算基线 |
| `/benchmark/provider/direct/sleep?millis=100` | GET | provider 本地慢调用基线 |

consumer RPC 压测接口：

| 接口 | 方法 | 用途 |
| --- | --- | --- |
| `/benchmark/rpc/health` | GET | consumer 进程健康检查 |
| `/benchmark/rpc/hello?name=jmeter` | GET | 普通 RPC 调用 |
| `/benchmark/rpc/add?a=1&b=2` | GET | RPC 计算调用 |
| `/benchmark/rpc/payload?size=1024` | GET | RPC 大 payload 序列化和网络传输 |
| `/benchmark/rpc/sleep?millis=100` | GET | RPC 慢调用、超时、线程池堆积 |
| `/benchmark/rpc/unstable?name=jmeter&failurePercent=10` | GET | RPC 可控失败、重试、熔断 |
| `/benchmark/rpc/batch?count=100&operation=hello` | GET | 单个 HTTP 请求内批量执行多次 RPC |

建议 JMeter 优先压 `/benchmark/rpc/hello`、`/benchmark/rpc/add`、`/benchmark/rpc/payload`、`/benchmark/rpc/sleep`、`/benchmark/rpc/unstable`。`/batch` 更适合本地快速制造 RPC 压力，不适合直接用来计算单次 RPC 延迟。

### 1.4 环境要求

建议：

1. JDK 16+。
2. Maven 3.8+。
3. ZooKeeper 3.8.x。
4. JMeter 5.6+。
5. 监控工具至少准备一种：JDK Flight Recorder、VisualVM、Arthas、`jcmd`、`jstat`。

先记录环境：

```powershell
java -version
mvn -version
```

### 1.5 测试机器建议

最低要求：

1. provider、consumer、ZooKeeper 可以在同一台机器上跑通。
2. 如果要做像样的压测，建议至少拆成两台机器：
   - 机器 A：provider + ZooKeeper
   - 机器 B：pressure client / JMeter

原因：压测端和服务端在同一台机器时，CPU、网络、GC、线程调度会互相干扰，QPS 和延迟数据只能做初步参考。

## 2. 测试指标

每轮压测至少记录这些指标：

| 指标 | 含义 | 记录方式 |
| --- | --- | --- |
| QPS / TPS | 每秒成功调用数 | JMeter Summary Report、自定义 client 统计 |
| 平均延迟 | 平均响应时间 | JMeter 或 client 统计 |
| P95 / P99 延迟 | 长尾响应时间 | JMeter Aggregate Report 或 client histogram |
| 错误率 | 失败请求占比 | JMeter error % 或 client 失败数 |
| CPU | provider / consumer CPU | 任务管理器、VisualVM、JFR |
| Heap 使用 | 是否持续上涨 | VisualVM、JFR、`jcmd GC.heap_info` |
| GC | GC 次数和耗时 | JFR、GC log、`jstat -gcutil` |
| 线程数 | 是否持续上涨 | VisualVM、JStack、JFR |
| 连接数 | Netty 连接池是否稳定 | 日志、metrics、系统连接统计 |
| pending request | 请求堆积情况 | `RequestManager` / runtime metrics |

测试结果不要只写“通过”。至少要写：

```text
并发数：
持续时间：
总请求数：
成功数：
失败数：
平均延迟：
P95：
P99：
最大延迟：
provider CPU：
provider heap：
错误日志：
结论：
```

## 3. 测试前基线准备

### 3.1 构建项目

在仓库根目录执行：

```powershell
mvn clean test
```

如果测试失败，不要直接开始压测。先记录失败模块和失败用例。

### 3.2 启动 ZooKeeper

Docker：

```powershell
docker run --name rpc-zk-test -p 2181:2181 -d zookeeper:3.8.4
```

检查：

```powershell
docker ps
```

### 3.3 启动 provider

打开 provider 终端：

```powershell
$env:RPC_REGISTRY_ADDRESS = "127.0.0.1:2181"
mvn -pl example-provider -am spring-boot:run -Dspring-boot.run.arguments="--rpc.registry.address=127.0.0.1:2181 --rpc.server.port=19090"
```

记录：

1. provider 启动时间。
2. RPC server 端口。
3. 是否成功注册到 ZooKeeper。
4. 是否有异常日志。

### 3.4 做一次冒烟调用

打开 consumer 终端：

```powershell
$env:RPC_REGISTRY_ADDRESS = "127.0.0.1:2181"
mvn -pl example-consumer -am spring-boot:run -Dspring-boot.run.arguments="--rpc.registry.address=127.0.0.1:2181"
```

预期：

```text
Hello, consumer!
1 + 2 = 3
```

冒烟调用成功后再开始压测。

## 4. 压测方案 A：自定义 RPC 压测客户端

这是推荐路线，因为它直接压 RPC client 到 provider 的链路，不混入 HTTP。

### 4.1 浏览器压测控制台

如果你希望边压测边看实时数据，优先使用浏览器控制台。

启动顺序：

1. 启动 ZooKeeper。
2. 启动 provider。
3. 启动 consumer。
4. 打开：

```text
http://127.0.0.1:18081/benchmark/console
```

页面上的核心参数：

| 参数 | 含义 |
| --- | --- |
| 压测方法 | 可选 `hello`、`add`、`payload`、`sleep`、`unstable`、`mixed` |
| 线程数 | consumer 进程中发起 RPC 调用的并发线程数 |
| 持续秒数 | 本轮压测持续时间 |
| payload 大小 | `payload` 和 `mixed` 方法使用的字符串大小 |
| sleep 毫秒 | `sleep` 和 `mixed` 方法使用的服务端 sleep 时间 |
| 失败比例 | `unstable` 和 `mixed` 方法使用的可控失败比例 |
| provider 指标地址 | provider 端 `/rpc/observability` JSON 地址 |

操作步骤：

1. 先点“立即刷新”，确认 consumer 和 provider 两端指标都能读取。
2. 第一轮使用 `hello`、`threads=1`、`durationSeconds=120` 做单线程基线。
3. 点击“开始压测”，观察“压测任务详情”和双端服务指标是否增长。
4. 逐步提高线程数，例如 10、50、100。
5. 每轮结束后记录页面上的 QPS、失败率、P95、P99、consumer 失败数、provider 失败数。
6. 如果 P99 明显升高或失败数持续增长，停止继续升并发，记录当前瓶颈点。

注意：该控制台运行在 `example-consumer` 进程内，所以压测线程也会消耗 consumer JVM 资源。需要更纯粹的外部压测时，再使用下面的 `example-benchmark` 可执行 jar。

### 4.2 运行内置压测客户端

项目已经内置 `example-benchmark` 模块。它不会启动 HTTP 服务，而是直接创建 `RpcConsumerBootstrap`，拿到 `HelloService` 代理后循环调用 provider。

先确保 ZooKeeper 和 provider 已经启动，然后在新的终端执行：

```powershell
mvn -pl example-benchmark -am package -DskipTests
java -jar .\example-benchmark\target\example-benchmark-1.0-SNAPSHOT.jar --registry=127.0.0.1:2181 --threads=10 --durationSeconds=300 --warmupSeconds=30 --method=hello
```

如果你只想看参数说明：

```powershell
java -jar .\example-benchmark\target\example-benchmark-1.0-SNAPSHOT.jar --help
```

压测客户端支持这些参数：

| 参数 | 含义 |
| --- | --- |
| `--registry=127.0.0.1:2181` | ZooKeeper 地址 |
| `--threads=50` | 并发线程数 |
| `--durationSeconds=300` | 正式统计持续时间 |
| `--warmupSeconds=30` | 预热时间，预热阶段不计入最终统计 |
| `--method=hello` | 压测方法，可选 `hello`、`hi`、`add`、`payload`、`sleep`、`unstable`、`mixed` |
| `--payloadSize=1024` | `payload` 和 `mixed` 方法使用的字符串大小 |
| `--sleepMillis=100` | `sleep` 和 `mixed` 方法使用的服务端 sleep 时间 |
| `--failurePercent=10` | `unstable` 和 `mixed` 方法使用的可控失败比例 |
| `--serializer=protobuf` | 覆盖序列化器 |
| `--loadbalancer=random` | 覆盖负载均衡器 |
| `--connectTimeout=5000` | 覆盖连接超时时间，单位毫秒 |
| `--readTimeout=10000` | 覆盖读取超时时间，单位毫秒 |
| `--retryTimes=3` | 覆盖默认重试次数 |
| `--maxConnectionsPerAddress=2` | 覆盖单个 provider 地址的最大连接数 |
| `--maxInflightRequestsPerConnection=256` | 覆盖单连接最大在途请求数 |
| `--maxPendingRequests=10000` | 覆盖 consumer 总 pending request 上限 |
| `--sampleLimit=1000000` | 最多保留多少条延迟样本用于计算 P50/P95/P99 |
| `--reportIntervalSeconds=5` | 中间进度输出间隔 |

每个线程会循环调用 RPC，并统计：

1. 成功次数。
2. 失败次数。
3. 平均耗时。
4. P95。
5. P99。
6. 最大耗时。
7. 异常类型 Top N。

最终输出中的 `latencySamples=已采样/总调用数` 表示百分位延迟基于采样数据计算；如果长稳测试调用量很大，可以适当调大 `--sampleLimit`，但不要无限调大，否则压测客户端自身会占用过多内存。

### 4.3 常用命令

单线程基线：

```powershell
java -jar .\example-benchmark\target\example-benchmark-1.0-SNAPSHOT.jar --registry=127.0.0.1:2181 --threads=1 --durationSeconds=120 --warmupSeconds=10 --method=hello
```

50 并发普通调用：

```powershell
java -jar .\example-benchmark\target\example-benchmark-1.0-SNAPSHOT.jar --registry=127.0.0.1:2181 --threads=50 --durationSeconds=600 --warmupSeconds=30 --method=hello
```

大 payload 序列化和网络传输：

```powershell
java -jar .\example-benchmark\target\example-benchmark-1.0-SNAPSHOT.jar --registry=127.0.0.1:2181 --threads=50 --durationSeconds=600 --warmupSeconds=30 --method=payload --payloadSize=10240
```

慢调用和超时压力：

```powershell
java -jar .\example-benchmark\target\example-benchmark-1.0-SNAPSHOT.jar --registry=127.0.0.1:2181 --threads=50 --durationSeconds=600 --warmupSeconds=30 --method=sleep --sleepMillis=100 --readTimeout=1000
```

可控失败、重试、熔断观察：

```powershell
java -jar .\example-benchmark\target\example-benchmark-1.0-SNAPSHOT.jar --registry=127.0.0.1:2181 --threads=50 --durationSeconds=600 --warmupSeconds=30 --method=unstable --failurePercent=10 --retryTimes=3
```

连接池和 pending request 压力：

```powershell
java -jar .\example-benchmark\target\example-benchmark-1.0-SNAPSHOT.jar --registry=127.0.0.1:2181 --threads=200 --durationSeconds=600 --warmupSeconds=30 --method=sleep --sleepMillis=100 --maxConnectionsPerAddress=1 --maxInflightRequestsPerConnection=16 --maxPendingRequests=100
```

混合场景：

```powershell
java -jar .\example-benchmark\target\example-benchmark-1.0-SNAPSHOT.jar --registry=127.0.0.1:2181 --threads=100 --durationSeconds=600 --warmupSeconds=30 --method=mixed --payloadSize=1024 --sleepMillis=10 --failurePercent=1
```

### 4.4 建议测试阶梯

第一轮不要直接上很高并发。按阶梯压：

| 阶段 | 并发 | 持续时间 | 目标 |
| --- | --- | --- | --- |
| baseline | 1 | 2 分钟 | 建立单线程基线 |
| light | 10 | 5 分钟 | 验证基本并发 |
| medium | 50 | 10 分钟 | 观察延迟和错误率 |
| high | 100 | 10 分钟 | 找到接近瓶颈的位置 |
| stress | 200+ | 10 分钟 | 找到失败拐点 |
| soak | 目标并发的 50%-70% | 2 小时以上 | 验证长时间稳定性 |

每个阶段之间至少间隔 2 分钟，观察 provider 是否恢复到稳定状态。

### 4.5 判定标准

建议先用下面标准作为第一版：

| 指标 | 通过标准 |
| --- | --- |
| 错误率 | baseline/light/medium 阶段小于 0.1% |
| P99 | medium 阶段不应出现持续秒级长尾 |
| Heap | soak 阶段不应持续单调上涨且不回落 |
| 线程数 | 稳态后不应持续增加 |
| provider | 不应崩溃或出现无法恢复的连接异常 |
| consumer | 不应出现 pending request 持续堆积 |

这些不是最终生产 SLA，只是第一轮项目验证标准。后续可以根据机器配置和业务目标重新定义。

## 5. 压测方案 B：JMeter HTTP Sampler

当前项目已经提供 consumer HTTP 压测入口，所以你可以直接用 JMeter HTTP Request 来压 RPC 链路。

### 5.1 新建测试计划

在 JMeter 中操作：

1. 打开 JMeter。
2. `File` -> `New`。
3. 右键 `Test Plan` -> `Add` -> `Threads (Users)` -> `Thread Group`。
4. 设置 Thread Group：
   - Name：`RPC Benchmark - hello`
   - Number of Threads：`10`
   - Ramp-up period：`30`
   - Loop Count：勾选 `Infinite`
   - Scheduler：勾选
   - Duration：`300`

第一轮先用 10 线程跑 5 分钟，不要一开始就上高并发。

### 5.2 添加 HTTP Request Defaults

右键 `Thread Group`：

```text
Add -> Config Element -> HTTP Request Defaults
```

配置：

| 字段 | 值 |
| --- | --- |
| Protocol | `http` |
| Server Name or IP | `127.0.0.1` |
| Port Number | `18081` |

说明：`18081` 是 consumer 的 Spring Boot Web 端口。JMeter 打 consumer，consumer 再调用 provider。

### 5.3 添加 HTTP Request：普通 RPC 调用

右键 `Thread Group`：

```text
Add -> Sampler -> HTTP Request
```

配置：

| 字段 | 值 |
| --- | --- |
| Name | `RPC hello` |
| Method | `GET` |
| Path | `/benchmark/rpc/hello` |

Parameters：

| Name | Value |
| --- | --- |
| `name` | `jmeter` |

### 5.4 添加断言

右键 `RPC hello`：

```text
Add -> Assertions -> Response Assertion
```

配置：

1. Field to Test：`Text Response`
2. Pattern Matching Rules：`Substring`
3. Patterns to Test 添加：

```text
Hello, jmeter!
```

这个断言用于确认请求不是只返回 200，而是真的完成了 RPC 调用。

### 5.5 添加报告

右键 `Thread Group` 添加：

```text
Add -> Listener -> Summary Report
Add -> Listener -> Aggregate Report
```

调试阶段可以临时加：

```text
Add -> Listener -> View Results Tree
```

但正式压测时要禁用 `View Results Tree`，否则它会明显拖慢 JMeter。

### 5.6 压 payload

复制 `RPC hello`，改名为 `RPC payload`：

| 字段 | 值 |
| --- | --- |
| Method | `GET` |
| Path | `/benchmark/rpc/payload` |

Parameters：

| Name | Value |
| --- | --- |
| `size` | `1024` |

建议分别测：

1. `size=128`
2. `size=1024`
3. `size=10240`
4. `size=65536`

不要一开始就用 1MB payload，否则你测到的可能主要是 HTTP、序列化和本机内存分配压力。

### 5.7 压慢调用

新增 HTTP Request：

| 字段 | 值 |
| --- | --- |
| Name | `RPC sleep` |
| Method | `GET` |
| Path | `/benchmark/rpc/sleep` |

Parameters：

| Name | Value |
| --- | --- |
| `millis` | `100` |

建议分别测：

1. `millis=10`
2. `millis=100`
3. `millis=500`
4. `millis=1000`

这个接口主要用来观察 provider 业务线程池、队列堆积、consumer 超时和 P99 长尾。

### 5.8 压可控失败接口

新增 HTTP Request：

| 字段 | 值 |
| --- | --- |
| Name | `RPC unstable` |
| Method | `GET` |
| Path | `/benchmark/rpc/unstable` |

Parameters：

| Name | Value |
| --- | --- |
| `name` | `jmeter` |
| `failurePercent` | `10` |

预期：

1. 会出现部分失败。
2. JMeter Error % 会大于 0。
3. consumer / provider 的 dashboard 中失败计数会变化。

这个接口适合验证失败率、重试、熔断、降级策略，但不要和普通吞吐测试混在同一个报告里。

### 5.9 使用 CSV 参数化

右键 `Thread Group`：

```text
Add -> Config Element -> CSV Data Set Config
```

创建 CSV 文件，例如 `jmeter-rpc-data.csv`：

```text
name,a,b,payloadSize,sleepMillis,failurePercent
alice,1,2,128,10,0
bob,10,20,1024,100,10
carol,100,200,10240,500,20
```

CSV Data Set Config：

| 字段 | 值 |
| --- | --- |
| Filename | `D:/aaaRPC/my-rpc-framework/doc/05-testing/jmeter-rpc-data.csv` |
| Variable Names | `name,a,b,payloadSize,sleepMillis,failurePercent` |
| Ignore first line | `True` |
| Recycle on EOF | `True` |
| Stop thread on EOF | `False` |

HTTP Request 参数中使用：

```text
${name}
${a}
${b}
${payloadSize}
${sleepMillis}
${failurePercent}
```

### 5.10 阶梯压测配置

建议保存多个 Test Plan，或者每轮手动调整 Thread Group：

| 轮次 | Threads | Ramp-up | Duration | Sampler |
| --- | --- | --- | --- | --- |
| J-01 | 10 | 30s | 5min | `/benchmark/rpc/hello` |
| J-02 | 50 | 60s | 10min | `/benchmark/rpc/hello` |
| J-03 | 100 | 120s | 10min | `/benchmark/rpc/hello` |
| J-04 | 50 | 60s | 10min | `/benchmark/rpc/payload?size=1024` |
| J-05 | 50 | 60s | 10min | `/benchmark/rpc/sleep?millis=100` |
| J-06 | 50 | 60s | 10min | `/benchmark/rpc/unstable?failurePercent=10` |
| J-07 | 目标并发 | 300s | 2h | `/benchmark/rpc/hello` |

每轮结束后记录：

1. Samples。
2. Average。
3. Median。
4. 90% Line。
5. 95% Line。
6. 99% Line。
7. Min / Max。
8. Error %。
9. Throughput。
10. provider dashboard 截图。
11. consumer dashboard 截图。

## 6. 压测方案 C：JMeter Java Request / JSR223

如果你希望尽量接近“纯 RPC”性能，不要使用 HTTP Sampler 的结果作为最终结论。更合适的是：

1. 写一个 Java Sampler 或 JSR223 Sampler。
2. 在 sampler 里初始化 `RpcConsumerBootstrap` 或通过 Spring 拿到 `HelloService` 代理。
3. 每次 sample 调用一次 `helloService.sayHello(...)` 或 `helloService.add(...)`。
4. 让 JMeter 负责线程数、Ramp-Up、持续时间和统计报表。

### 6.1 JMeter 测试计划建议

Thread Group：

| 参数 | 建议值 |
| --- | --- |
| Number of Threads | 从 10 开始，逐步增加 |
| Ramp-up period | 30s |
| Loop Count | Forever |
| Duration | 300s 起步 |

Listeners：

1. Summary Report。
2. Aggregate Report。
3. Backend Listener，如果你有 InfluxDB / Grafana。

不要在高压测试时开启 View Results Tree，它会严重影响压测结果。

### 6.2 JMeter 阶梯压测

按下面轮次执行：

| 轮次 | Threads | Ramp-up | Duration |
| --- | --- | --- | --- |
| J-01 | 10 | 30s | 5min |
| J-02 | 50 | 60s | 10min |
| J-03 | 100 | 120s | 10min |
| J-04 | 200 | 180s | 10min |
| J-05 | 目标并发 | 300s | 2h |

每轮记录：

1. Samples。
2. Average。
3. Median。
4. 90% Line。
5. 95% Line。
6. 99% Line。
7. Min / Max。
8. Error %。
9. Throughput。

### 6.3 JMeter 结果判读

重点看四件事：

1. Throughput 是否随着线程数上升而上升。
2. 到某个并发后，Throughput 不再增加但 P99 明显变差，这通常是瓶颈点。
3. Error % 是否在高并发或长时间运行时上升。
4. provider 侧 CPU、GC、线程和连接数是否同步异常。

## 7. 压测方案 D：HTTP wrapper + JMeter HTTP Sampler 结果解释

当前项目已经内置了 HTTP wrapper：

```text
GET /benchmark/rpc/hello?name=test
GET /benchmark/rpc/add?a=1&b=2
```

这些接口内部调用 `HelloService` 代理。

注意：这种方案测到的是：

```text
JMeter -> HTTP server -> RPC consumer -> RPC provider
```

它不是纯 RPC 性能。报告中必须写清楚“包含 HTTP wrapper 开销”。

适合回答：

1. 这个 RPC 框架接入 Web 应用后的整体吞吐如何。
2. Web 入口 + RPC 后端的端到端延迟如何。

不适合回答：

1. 纯 RPC transport 极限 QPS。
2. Netty client 自身最大能力。

## 8. 生产稳定性专项测试

### 8.1 长稳测试

目标：观察内存、线程、连接、pending request 是否泄漏。

建议参数：

| 项 | 值 |
| --- | --- |
| 并发 | medium 压测中稳定并发的 50%-70% |
| 持续时间 | 2 小时起步，最好 8 小时 |
| 方法 | `sayHello` 和 `add` 混合 |
| 序列化器 | 默认 `protobuf`，再选一个 `json` 做对比 |

每 10 分钟记录：

1. QPS。
2. P95 / P99。
3. provider heap。
4. provider 线程数。
5. GC 次数和耗时。
6. 错误率。

通过标准：

1. QPS 没有持续下滑。
2. P99 没有持续恶化。
3. Heap 没有持续上涨不回落。
4. 线程数没有持续增长。
5. 错误率没有随着运行时间升高。

### 8.2 冲击测试

目标：验证突然流量冲击下是否出现不可恢复异常。

步骤：

1. 先以 10 并发运行 2 分钟。
2. 1 分钟内提升到 200 并发。
3. 保持 5 分钟。
4. 降回 10 并发。
5. 观察系统是否恢复。

记录：

1. 峰值时错误率。
2. 峰值时 P99。
3. 降载后 P99 是否恢复。
4. provider 是否出现 OOM、线程池拒绝、连接池异常。

### 8.3 线程池和队列饱和测试

目标：验证 provider 业务线程池压力过大时的表现。

做法：

1. 降低 provider 业务线程池和队列容量。
2. 提高 consumer 并发。
3. 观察请求是否超时、被限流、被拒绝或堆积。

示例参数：

```powershell
mvn -pl example-provider -am spring-boot:run -Dspring-boot.run.arguments="--rpc.registry.address=127.0.0.1:2181 --rpc.server.port=19090 --rpc.server.biz.core-threads=2 --rpc.server.biz.max-threads=2 --rpc.server.biz.queue-capacity=10"
```

通过标准：

1. 系统可以失败，但失败方式应可解释。
2. 不应出现 provider JVM 崩溃。
3. 降载后系统应能恢复。

### 8.4 连接池压力测试

目标：验证 consumer 侧连接池、inflight 限制和 pending request 表现。

示例 consumer 参数：

```powershell
--rpc.client.max-connections-per-address=1 --rpc.client.max-inflight-requests-per-connection=16 --rpc.client.max-pending-requests=100
```

观察：

1. 达到限制时是否快速失败或排队。
2. pending request 是否持续增长。
3. 请求超时后是否能正确清理。
4. provider 恢复后 consumer 是否能继续调用。

### 8.5 序列化器对比测试

目标：对比不同序列化器的吞吐、延迟和错误率。

测试值：

1. `protobuf`
2. `json`
3. `kryo`
4. `hessian`
5. `java`

每个序列化器至少跑：

1. 10 并发 5 分钟。
2. 50 并发 10 分钟。

记录：

1. QPS。
2. 平均延迟。
3. P99。
4. CPU。
5. GC。
6. 错误率。

注意：provider 和 consumer 必须使用同一种序列化器。

### 8.6 多 provider 负载均衡测试

目标：验证 consumer 能发现多个 provider，并按负载均衡策略选择实例。

启动多个 provider，端口不同：

```powershell
mvn -pl example-provider -am spring-boot:run -Dspring-boot.run.arguments="--rpc.registry.address=127.0.0.1:2181 --rpc.server.port=19090"
```

另一个终端：

```powershell
mvn -pl example-provider -am spring-boot:run -Dspring-boot.run.arguments="--rpc.registry.address=127.0.0.1:2181 --rpc.server.port=19091"
```

consumer 分别测试：

```text
--rpc.loadbalancer=random
--rpc.loadbalancer=roundRobin
--rpc.loadbalancer=leastConnections
--rpc.loadbalancer=consistentHash
```

记录：

1. 两个 provider 是否都有请求。
2. roundRobin 是否大致轮询。
3. 停掉一个 provider 后，consumer 是否能继续调用另一个 provider。

如果当前日志无法区分 provider 实例，需要先在 provider 日志或返回值中加入端口/实例标识，测试完成后再恢复。

## 9. 故障注入测试

### 9.1 provider 进程直接停止

步骤：

1. 压测运行中。
2. 直接停止 provider 进程。
3. 观察 consumer 错误率、超时、重试、熔断。
4. 重新启动 provider。
5. 观察 consumer 是否恢复。

记录：

1. 停止后多久开始失败。
2. 失败类型。
3. 重启后多久恢复。
4. 是否需要重启 consumer。

### 9.2 ZooKeeper 短暂中断

步骤：

1. 压测运行中。
2. 停止 ZooKeeper 30 秒。
3. 重新启动 ZooKeeper。
4. 观察 consumer 是否能继续使用缓存实例或恢复发现。

记录：

1. 中断期间成功率。
2. 是否使用 stale cache。
3. ZooKeeper 恢复后服务目录是否刷新。

### 9.3 网络端口不可达

Windows 本地可以用防火墙规则，或直接停 provider 来替代。Linux 环境可用 `iptables` / `tc` 做更细的网络故障。

记录：

1. connect timeout 是否生效。
2. read timeout 是否生效。
3. reconnect 是否按配置退避。
4. 日志是否能定位目标地址。

### 9.4 慢调用

如果要测试超时、重试、熔断，最好临时给 provider 增加一个慢方法，例如 sleep 200ms / 1s。当前 `HelloService` 方法太快，本地环境不一定能稳定触发超时。

测试完成后记录：

1. 慢调用耗时。
2. consumer read timeout。
3. retry 次数。
4. 熔断器状态变化。

## 10. 观测工具建议

### 10.1 JFR

启动 provider 时增加 JVM 参数更适合长期观察。也可以运行中启动：

```powershell
jcmd <pid> JFR.start name=rpc-test settings=profile filename=rpc-provider.jfr
jcmd <pid> JFR.stop name=rpc-test
```

重点看：

1. CPU hot methods。
2. Allocation。
3. GC pause。
4. Socket read/write。
5. Thread park / blocked。

### 10.2 jstat

```powershell
jstat -gcutil <pid> 1000
```

观察：

1. Old 区是否持续上涨。
2. Full GC 是否频繁。
3. YGC / FGC 次数是否随压测异常增长。

### 10.3 jstack

```powershell
jstack <pid> > provider-thread-dump.txt
```

在 P99 突然升高或系统卡住时抓线程栈，重点看：

1. Netty event loop 是否阻塞。
2. 业务线程池是否耗尽。
3. 是否有大量线程卡在 ZooKeeper、序列化、反射调用或锁竞争上。

### 10.4 VisualVM / Arthas

用于观察：

1. Heap 曲线。
2. 线程数曲线。
3. 类加载数量。
4. 方法耗时。
5. 热点方法。

## 11. 面试中容易被问的测试方向

可以准备下面这些回答点：

1. 你怎么压测这个 RPC？
   - 直接用 RPC 压测客户端或 JMeter Java Sampler，不把 HTTP wrapper 的结果当作纯 RPC 性能。
2. 你看哪些指标？
   - QPS、平均延迟、P95、P99、错误率、CPU、Heap、GC、线程数、连接数、pending request。
3. 怎么验证没有内存泄漏？
   - 做 2-8 小时长稳测试，看 heap 是否持续上涨不回落，并结合 JFR / VisualVM / jstat。
4. 怎么验证熔断和重试？
   - 注入慢调用、停 provider、制造超时，观察错误率、恢复时间、熔断状态和日志。
5. 怎么验证负载均衡？
   - 启动多个 provider 实例，记录每个实例收到的请求数量。
6. 怎么验证注册中心异常？
   - 压测中停 ZooKeeper，再恢复，观察服务目录缓存和调用恢复。
7. 怎么定义“能抗住压力”？
   - 不能只说能跑，要给出目标并发、QPS、P99、错误率和持续时间。
8. 为什么 JMeter HTTP 压测不等于 RPC 压测？
   - HTTP wrapper 会引入 Web 容器、HTTP 编解码和 Controller 开销，只能代表端到端应用链路。

## 12. 推荐测试顺序

按这个顺序执行：

1. 冒烟调用：确认 provider / consumer 正常。
2. baseline：1 并发 2 分钟。
3. light：10 并发 5 分钟。
4. medium：50 并发 10 分钟。
5. high：100 并发 10 分钟。
6. stress：继续升并发直到出现明显瓶颈。
7. soak：用稳定并发跑 2 小时以上。
8. fault：provider 停止、ZooKeeper 停止、provider 重启。
9. config：序列化器、负载均衡、线程池、连接池配置对比。
10. summary：整理结果，写出当前项目的性能边界和风险。
