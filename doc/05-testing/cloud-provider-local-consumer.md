# 云服务器 Provider + 本地 Consumer 压测步骤

这份文档用于执行下面这种部署方式：

```text
云服务器：ZooKeeper + example-provider
本地电脑：example-consumer + 压测控制台页面
```

推荐使用 SSH 隧道，不直接把 ZooKeeper `2181` 和 RPC 服务端口 `19090` 暴露到公网。这样安全一些，也能绕开当前框架中 `rpc.server.host` 同时承担“监听地址”和“注册地址”的限制。

你的云服务器公网 IP 示例：

```text
8.134.204.101
```

后续命令里如果 IP 不一致，以你的实际服务器 IP 为准。

## 1. 为什么推荐 SSH 隧道

当前 provider 注册服务时使用的是：

```text
rpc.server.host + rpc.server.port
```

也就是说，如果 provider 用：

```text
rpc.server.host=127.0.0.1
rpc.server.port=19090
```

注册中心里会出现：

```text
com.rpc.HelloService -> 127.0.0.1:19090
```

如果本地 consumer 直接连云服务器上的 ZooKeeper，它会拿到 `127.0.0.1:19090`，然后尝试连接本地电脑自己的 `127.0.0.1:19090`，正常情况下会失败。

SSH 隧道可以把本地电脑的端口转发到云服务器，所以本地 consumer 连接 `127.0.0.1:19090` 时，实际会被转发到云服务器上的 provider。

## 2. 端口规划

| 用途 | 云服务器端口 | 本地端口 | 说明 |
| --- | --- | --- | --- |
| SSH | `22` | - | 用于建立隧道 |
| ZooKeeper | `2181` | `12181` | 本地 consumer 连接 `127.0.0.1:12181` |
| RPC provider | `19090` | `19090` | 注册中心里是 `127.0.0.1:19090`，本地通过隧道访问 |
| provider Web 指标 | `18080` | `18080` | 压测页面读取 provider 端指标 |
| consumer Web 页面 | - | `18081` | 本地启动 consumer 后访问 |

云服务器安全组只需要放行：

```text
22
```

如果你不使用 SSH 隧道，而是直接公网访问，才需要额外放行 `2181`、`19090`、`18080`。不建议这么做，尤其不建议把 ZooKeeper `2181` 暴露公网。

## 3. 云服务器准备

### 3.1 停掉 RabbitMQ

你已经执行了：

```bash
docker stop <rabbitmq-container-id>
```

可以再次确认：

```bash
docker ps
```

预期只保留 ZooKeeper，例如：

```text
zookeeper:3.5.8   0.0.0.0:2181->2181/tcp
```

### 3.2 检查 Java 和 Maven

在云服务器执行：

```bash
java -version
mvn -version
```

如果没有安装：

```bash
dnf install -y java-17-openjdk-devel maven git
```

如果你的系统没有 `dnf`，改用：

```bash
yum install -y java-17-openjdk-devel maven git
```

### 3.3 上传或拉取项目

如果使用 Git：

```bash
git clone <你的仓库地址>
cd my-rpc-framework
```

如果使用 FinalShell 上传，把整个项目上传到云服务器，例如：

```text
/root/my-rpc-framework
```

然后进入目录：

```bash
cd /root/my-rpc-framework
```

### 3.4 构建项目

```bash
mvn -pl example-provider -am package -DskipTests
```

## 4. 云服务器启动 provider

在云服务器项目根目录执行：

```bash
export RPC_REGISTRY_ADDRESS=127.0.0.1:2181

mvn -pl example-provider -am spring-boot:run \
  -Dspring-boot.run.arguments="--rpc.registry.address=127.0.0.1:2181 --rpc.server.host=127.0.0.1 --rpc.server.port=19090 --server.port=18080"
```

注意这里故意使用：

```text
rpc.server.host=127.0.0.1
```

因为后面会通过 SSH 隧道把本地 `127.0.0.1:19090` 转发到云服务器 `127.0.0.1:19090`。

provider 启动后，日志里应该能看到类似信息：

```text
Registered service to registry center: com.rpc.HelloService@127.0.0.1:19090
```

先不要关闭这个 provider 终端。

## 5. 本地电脑建立 SSH 隧道

在本地 Windows PowerShell 打开一个新终端，执行：

```powershell
ssh -N `
  -L 12181:127.0.0.1:2181 `
  -L 19090:127.0.0.1:19090 `
  -L 18080:127.0.0.1:18080 `
  root@8.134.204.101
```

如果你不用反引号换行，也可以写成一行：

```powershell
ssh -N -L 12181:127.0.0.1:2181 -L 19090:127.0.0.1:19090 -L 18080:127.0.0.1:18080 root@8.134.204.101
```

这个终端会一直挂住，这是正常的。不要关闭它，关闭后隧道就断了。

如果你本地 `19090` 或 `18080` 已被占用，可以先关闭占用进程；不建议随便改这两个本地端口，因为 ZooKeeper 里注册的是 `127.0.0.1:19090`。

## 6. 本地验证隧道

新开一个本地 PowerShell 终端。

### 6.1 验证 provider Web 指标

```powershell
curl "http://127.0.0.1:18080/rpc/observability?includeServices=true&limit=200"
```

能返回 JSON 就说明 `18080` 隧道正常。

### 6.2 验证 ZooKeeper 端口

如果你有 `Test-NetConnection`：

```powershell
Test-NetConnection 127.0.0.1 -Port 12181
```

预期：

```text
TcpTestSucceeded : True
```

### 6.3 验证 RPC 端口

```powershell
Test-NetConnection 127.0.0.1 -Port 19090
```

预期：

```text
TcpTestSucceeded : True
```

## 7. 本地启动 consumer

在本地项目根目录执行：

```powershell
$env:RPC_REGISTRY_ADDRESS = "127.0.0.1:12181"

mvn -pl example-consumer -am spring-boot:run -Dspring-boot.run.arguments="--rpc.registry.address=127.0.0.1:12181 --server.port=18081"
```

这里 consumer 连接的是本地端口：

```text
127.0.0.1:12181
```

它会通过 SSH 隧道访问云服务器上的 ZooKeeper。

## 8. 打开压测控制台

本地浏览器打开：

```text
http://127.0.0.1:18081/benchmark/console
```

页面里的 provider 指标地址填写：

```text
http://127.0.0.1:18080/rpc/observability?includeServices=true&limit=200
```

注意这里仍然使用 `127.0.0.1:18080`，因为浏览器访问的是本地 consumer，consumer 后端会通过 SSH 隧道读取云服务器 provider 指标。

点击“立即刷新”，确认 provider 服务指标能显示。

## 9. 第一轮压测参数

先跑小流量，不要直接上高并发。

| 参数 | 值 |
| --- | --- |
| 压测方法 | `hello` |
| 线程数 | `1` |
| 持续秒数 | `30` |
| payload 大小 | `1024` |
| sleep 毫秒 | `0` |
| 失败比例 | `0` |

操作：

1. 点击“立即刷新”，截图保存为 `01-cloud-smoke-before.png`。
2. 点击“开始压测”。
3. 等待状态变成“已停止”。
4. 点击“立即刷新”，截图保存为 `01-cloud-smoke-after.png`。

截图建议放到：

```text
doc/05-testing/img/
```

## 10. 推荐压测顺序

这台云服务器是 2C2G，provider 和 ZooKeeper 在云端，本地 consumer 负责发压。可以比单机方案更合理，但云服务器仍然不适合直接打很高并发。

建议按下面顺序跑：

| 轮次 | 方法 | 线程数 | 持续秒数 | 其他参数 |
| --- | --- | --- | --- | --- |
| 1 | `hello` | 1 | 30 | 冒烟 |
| 2 | `hello` | 1 | 120 | 单线程基线 |
| 3 | `hello` | 5 | 300 | 小并发 |
| 4 | `hello` | 10 | 300 | 轻量并发 |
| 5 | `payload` | 10 | 300 | `payloadSize=1024` |
| 6 | `payload` | 10 | 300 | `payloadSize=10240` |
| 7 | `sleep` | 10 | 300 | `sleepMillis=100` |
| 8 | `unstable` | 10 | 120 | `failurePercent=10` |
| 9 | `mixed` | 10 | 300 | `payloadSize=1024`，`sleepMillis=10`，`failurePercent=1` |

如果 10 线程下稳定，再考虑跑：

| 方法 | 线程数 | 持续秒数 |
| --- | --- | --- |
| `hello` | 20 | 300 |
| `hello` | 30 | 300 |

如果出现下面任一情况，就先停止升并发：

1. provider CPU 长时间接近 100%。
2. provider 内存持续上涨且不回落。
3. P99 明显升高，例如从毫秒级变成几十毫秒、几百毫秒。
4. 失败数持续增长。
5. consumer 日志出现大量超时、重连或 pending request 拒绝。

## 11. 常见问题

### 11.1 consumer 启动后找不到 provider

检查三件事：

1. provider 是否还在运行。
2. SSH 隧道终端是否还开着。
3. consumer 是否使用了 `127.0.0.1:12181` 作为注册中心。

### 11.2 provider 指标页面刷新失败

本地执行：

```powershell
curl "http://127.0.0.1:18080/rpc/observability?includeServices=true&limit=200"
```

如果失败，说明 `18080` 隧道没有建好，或者 provider 没启动。

### 11.3 RPC 调用失败但 ZooKeeper 能连

本地执行：

```powershell
Test-NetConnection 127.0.0.1 -Port 19090
```

如果失败，说明 `19090` 隧道没有建好，或者 provider 的 RPC server 没启动。

### 11.4 本地 19090 被占用怎么办

先查占用：

```powershell
netstat -ano | findstr :19090
```

如果是旧的测试进程，结束它。因为注册中心里 provider 地址是 `127.0.0.1:19090`，本地端口最好保持一致。

## 12. 面试中怎么解释这套测试

可以这样说：

```text
我没有把 provider、consumer、压测端全部放在一台机器上测，而是把 provider 和 ZooKeeper 放到云服务器，本地运行 consumer 压测端。
这样至少能把服务端和压测端分开，减少单机资源争用对结果的干扰。

同时我没有直接把 ZooKeeper 和 RPC 端口暴露到公网，而是通过 SSH 隧道转发端口，避免测试环境引入不必要的安全风险。

这套测试能观察跨机器调用下的 QPS、P95、P99、失败率，以及 consumer/provider 两端指标差异。
但由于云服务器只有 2C2G，所以测试结论主要用于项目演示和趋势分析，不作为生产容量上限。
```
