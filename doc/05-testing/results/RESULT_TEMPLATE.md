# 生产可用性与压测结果记录模板

## 1. 基本信息

| 项 | 内容 |
| --- | --- |
| 测试日期 |  |
| 测试人 |  |
| Git 分支 |  |
| Git commit |  |
| 测试目标 |  |
| 测试机器 |  |
| Provider 机器 |  |
| Consumer / JMeter 机器 |  |
| JDK 版本 |  |
| Maven 版本 |  |
| ZooKeeper 地址 |  |
| 压测工具 |  |

## 2. 测试结论

选择一个：

- 通过
- 部分通过
- 阻塞

结论摘要：

```text

```

当前性能边界：

```text
稳定并发：
稳定 QPS：
平均延迟：
P95：
P99：
错误率：
主要瓶颈：
```

## 3. 环境和配置

Provider 启动参数：

```text

```

Consumer / 压测客户端参数：

```text

```

RPC 配置：

| 配置项 | 值 |
| --- | --- |
| `rpc.serializer` |  |
| `rpc.loadbalancer` |  |
| `rpc.client.read-timeout` |  |
| `rpc.client.retry-times` |  |
| `rpc.client.cluster` |  |
| `rpc.client.max-connections-per-address` |  |
| `rpc.client.max-inflight-requests-per-connection` |  |
| `rpc.server.biz.core-threads` |  |
| `rpc.server.biz.max-threads` |  |
| `rpc.server.biz.queue-capacity` |  |

## 4. 压测结果

| 轮次 | 工具 | 并发 | Ramp-up | 持续时间 | Samples | 成功数 | 失败数 | QPS | Avg | P95 | P99 | Max | Error % | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| baseline |  | 1 |  | 2min |  |  |  |  |  |  |  |  |  |  |
| light |  | 10 |  | 5min |  |  |  |  |  |  |  |  |  |  |
| medium |  | 50 |  | 10min |  |  |  |  |  |  |  |  |  |  |
| high |  | 100 |  | 10min |  |  |  |  |  |  |  |  |  |  |
| stress |  |  |  | 10min |  |  |  |  |  |  |  |  |  |  |
| soak |  |  |  | 2h+ |  |  |  |  |  |  |  |  |  |  |

## 5. 资源观测

| 时间点 | Provider CPU | Provider Heap | GC 情况 | 线程数 | 连接数 | pending request | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T+0 |  |  |  |  |  |  |  |
| T+10min |  |  |  |  |  |  |  |
| T+30min |  |  |  |  |  |  |  |
| T+60min |  |  |  |  |  |  |  |
| T+120min |  |  |  |  |  |  |  |

工具输出摘要：

```text
JFR:
jstat:
jstack:
VisualVM / Arthas:
```

## 6. 故障注入结果

| 场景 | 执行方式 | 预期 | 实际 | 恢复时间 | 结论 |
| --- | --- | --- | --- | --- | --- |
| provider 进程停止 |  |  |  |  |  |
| provider 重启 |  |  |  |  |  |
| ZooKeeper 停止 30s |  |  |  |  |  |
| 注册中心地址错误 |  |  |  |  |  |
| 慢调用 / 超时 |  |  |  |  |  |
| 线程池队列饱和 |  |  |  |  |  |

异常日志摘要：

```text

```

## 7. 配置对比

### 7.1 序列化器

| 序列化器 | 并发 | QPS | Avg | P95 | P99 | Error % | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| protobuf |  |  |  |  |  |  |  |
| json |  |  |  |  |  |  |  |
| kryo |  |  |  |  |  |  |  |
| hessian |  |  |  |  |  |  |  |
| java |  |  |  |  |  |  |  |

### 7.2 负载均衡

| 负载均衡器 | Provider 数 | 请求分布 | QPS | P99 | Error % | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| random |  |  |  |  |  |  |
| roundRobin |  |  |  |  |  |  |
| leastConnections |  |  |  |  |  |  |
| consistentHash |  |  |  |  |  |  |

### 7.3 线程池 / 连接池

| 配置组合 | 并发 | QPS | P99 | Error % | 现象 |
| --- | --- | --- | --- | --- | --- |
| 默认 |  |  |  |  |  |
| 小业务线程池 |  |  |  |  |  |
| 小连接池 |  |  |  |  |  |
| 小 pending queue |  |  |  |  |  |

## 8. 问题清单

| 编号 | 问题 | 严重程度 | 复现步骤 | 影响 | 下一步 |
| --- | --- | --- | --- | --- | --- |
| PERF-001 |  |  |  |  |  |

## 9. 面试可复述结论

```text
我用什么工具压测：
压测链路是否包含 HTTP：
最高稳定并发：
最高稳定 QPS：
P99 延迟：
错误率：
稳定运行时长：
故障注入结果：
发现的问题：
下一步优化：
```

