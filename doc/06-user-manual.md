# RPC 框架使用说明书

本文面向“要把这个 RPC 框架跑起来、接入服务、调整配置、部署和排障”的使用者。

如果你想系统学习源码，请看 `doc/README.md`。如果你想查类和调用链，请看 `doc/kb/00-index.md`。如果你想做压测，请看 `doc/05-testing/README.md`。

## 1. 项目能做什么

这个项目提供一套简化版 RPC 框架，让 consumer 像调用本地接口一样调用 provider 上的远程服务。

核心能力：

1. 使用接口定义服务契约。
2. provider 通过 `@RpcService` 发布服务。
3. consumer 通过 `@RpcReference` 注入远程代理。
4. 使用 ZooKeeper 做服务注册与发现。
5. 使用 Netty 做网络通信。
6. 支持序列化、负载均衡、重试、限流、熔断、降级、过滤器和可观测指标。
7. 提供 Spring Boot Starter 自动装配。

## 2. 环境要求

建议环境：

| 环境 | 要求 |
| --- | --- |
| JDK | 建议 JDK 16 或更高版本 |
| Maven | 3.6+ |
| ZooKeeper | 3.5+ |
| 操作系统 | Windows、Linux 均可 |

检查命令：

```bash
java -version
mvn -version
```

注意：

- 云服务器运行 jar 时不能使用 Java 8。
- 如果出现 `UnsupportedClassVersionError`，说明运行时 Java 版本低于项目编译版本，需要切换到 Java 11/16/17。

## 3. 模块说明

| 模块 | 作用 |
| --- | --- |
| `example-api` | 服务接口契约，provider 和 consumer 都依赖它 |
| `example-provider` | 服务提供方示例 |
| `example-consumer` | 服务调用方示例，包含压测控制台 |
| `example-benchmark` | 自定义 RPC 压测客户端 |
| `rpc-core` | RPC 核心能力 |
| `rpc-spring` | Spring 集成 |
| `rpc-spring-boot-starter` | Spring Boot 自动装配和可观测端点 |

最小运行链路：

```text
ZooKeeper
-> example-provider 注册服务
-> example-consumer 发现服务
-> consumer 通过 @RpcReference 调用 provider
```

## 4. 本地快速启动

### 4.1 启动 ZooKeeper

确保本地 ZooKeeper 监听：

```text
127.0.0.1:2181
```

如果 ZooKeeper 部署在其他机器，后续启动命令里需要修改 `rpc.registry.address`。

### 4.2 启动 provider

在项目根目录执行：

```powershell
mvn -pl example-provider -am spring-boot:run
```

如果你的 Maven 无法识别 `spring-boot:run`，使用完整插件坐标：

```powershell
mvn -pl example-provider -am org.springframework.boot:spring-boot-maven-plugin:2.7.18:run
```

provider 默认端口：

| 端口 | 作用 |
| --- | --- |
| `18080` | provider Web 和可观测接口 |
| `8080` 或启动参数指定端口 | RPC 服务端口，实际以配置为准 |

### 4.3 启动 consumer

新开一个终端，在项目根目录执行：

```powershell
mvn -pl example-consumer -am spring-boot:run
```

如果 Maven 插件前缀解析失败：

```powershell
mvn -pl example-consumer -am org.springframework.boot:spring-boot-maven-plugin:2.7.18:run
```

启动成功后，consumer 会调用：

```java
helloService.sayHello("consumer");
helloService.add(1, 2);
```

正常情况下能看到类似输出：

```text
Hello, consumer!
1 + 2 = 3
```

## 5. 使用可执行 jar 启动

### 5.1 打包 provider

```powershell
mvn -pl example-provider -am clean package -DskipTests
```

产物：

```text
example-provider/target/example-provider-1.0-SNAPSHOT.jar
```

检查是否为 Spring Boot 可执行 jar：

```powershell
jar tf .\example-provider\target\example-provider-1.0-SNAPSHOT.jar | Select-String -Pattern "^BOOT-INF/" | Select-Object -First 5
```

### 5.2 启动 provider jar

```bash
java -jar example-provider-1.0-SNAPSHOT.jar \
  --rpc.registry.address=127.0.0.1:2181 \
  --rpc.server.host=127.0.0.1 \
  --rpc.server.port=19090 \
  --server.port=18080
```

参数说明：

| 参数 | 作用 |
| --- | --- |
| `rpc.registry.address` | ZooKeeper 地址 |
| `rpc.server.host` | provider 注册到注册中心的 RPC 地址 |
| `rpc.server.port` | provider RPC 服务端口 |
| `server.port` | Spring Boot Web 端口 |

### 5.3 打包 consumer

```powershell
mvn -pl example-consumer -am clean package -DskipTests
```

产物：

```text
example-consumer/target/example-consumer-1.0-SNAPSHOT.jar
```

### 5.4 启动 consumer jar

```powershell
java -jar .\example-consumer\target\example-consumer-1.0-SNAPSHOT.jar --rpc.registry.address=127.0.0.1:2181 --server.port=18081
```

## 6. 接入一个新服务

### 6.1 在 api 模块定义接口

在 `example-api` 中新增接口，例如：

```java
package com.rpc;

public interface OrderService {
    String getOrder(String orderId);
}
```

接口应该只放服务契约，不要依赖 provider 的实现类。

### 6.2 在 provider 实现接口

在 provider 模块新增实现类：

```java
package com.rpc;

import com.rpc.core.api.annotation.RpcService;

@RpcService(OrderService.class)
public class OrderServiceImpl implements OrderService {
    @Override
    public String getOrder(String orderId) {
        return "order:" + orderId;
    }
}
```

关键点：

- 必须加 `@RpcService(OrderService.class)`。
- 实现类要能被 Spring Boot 扫描到。
- provider 和 consumer 都要依赖同一个 api 模块。

### 6.3 在 consumer 注入远程代理

```java
package com.rpc;

import com.rpc.core.api.annotation.RpcReference;
import org.springframework.stereotype.Component;

@Component
public class OrderClient {
    @RpcReference
    private OrderService orderService;

    public void test() {
        System.out.println(orderService.getOrder("1001"));
    }
}
```

consumer 只依赖接口，不依赖 provider 实现类。

## 7. 常用配置

### 7.1 注册中心

```yaml
rpc:
  registry:
    address: 127.0.0.1:2181
    timeout: 15000
```

常见写法：

```powershell
java -jar .\example-consumer\target\example-consumer-1.0-SNAPSHOT.jar --rpc.registry.address=127.0.0.1:2181
```

### 7.2 provider RPC 端口

```yaml
rpc:
  server:
    host: 127.0.0.1
    port: 19090
```

`rpc.server.host` 不只是监听地址，也会影响注册到 ZooKeeper 的地址。跨机器部署时要特别注意。

### 7.3 Spring Boot Web 端口

```yaml
server:
  port: 18080
```

provider 和 consumer 的 Web 端口建议不同：

| 应用 | 建议端口 |
| --- | --- |
| provider | `18080` |
| consumer | `18081` |

### 7.4 provider 限流

默认关闭。

启动 provider 时开启：

```bash
java -jar example-provider-1.0-SNAPSHOT.jar \
  --rpc.server.rate-limit.enabled=true \
  --rpc.server.rate-limit.permits-per-second=20
```

含义：

| 参数 | 作用 |
| --- | --- |
| `rpc.server.rate-limit.enabled` | 是否开启 provider 侧限流 |
| `rpc.server.rate-limit.permits-per-second` | 每秒允许通过的请求数 |

### 7.5 consumer 熔断/降级

默认关闭短路降级。

启动 consumer 时开启：

```powershell
java -jar .\example-consumer\target\example-consumer-1.0-SNAPSHOT.jar `
  --rpc.client.circuit-breaker.failure-rate-threshold=50 `
  --rpc.client.circuit-breaker.min-number-of-calls=10 `
  --rpc.client.circuit-breaker.wait-duration-in-open-state-millis=30000 `
  --rpc.client.circuit-breaker.permitted-half-open-calls=3 `
  --rpc.client.enable-degradation=true `
  --rpc.client.degradation.policy=failFast
```

说明：

| 参数 | 作用 |
| --- | --- |
| `failure-rate-threshold` | 失败率达到多少时打开熔断 |
| `min-number-of-calls` | 至少统计多少次调用后才判断失败率 |
| `wait-duration-in-open-state-millis` | 熔断打开后等待多久进入半开 |
| `permitted-half-open-calls` | 半开状态允许多少探测请求 |
| `enable-degradation` | 是否允许降级短路 |
| `degradation.policy` | 降级策略 |

## 8. 可观测接口和页面

### 8.1 provider 指标接口

```text
http://127.0.0.1:18080/rpc/observability?includeServices=true&limit=200
```

### 8.2 consumer 指标接口

```text
http://127.0.0.1:18081/rpc/observability?includeServices=true&limit=200
```

### 8.3 压测控制台

```text
http://127.0.0.1:18081/benchmark/console
```

压测控制台运行在 consumer 进程中，可以：

1. 发起 RPC 压测。
2. 查看当前压测任务 QPS、P99、成功数、失败数。
3. 查看 consumer 和 provider 双端指标。
4. 执行 hello、payload、sleep、unstable 等测试方法。

## 9. 自定义压测客户端

项目提供 `example-benchmark` 模块，用于绕过 HTTP，直接压 RPC 调用链。

打包：

```powershell
mvn -pl example-benchmark -am package -DskipTests
```

查看帮助：

```powershell
java -jar .\example-benchmark\target\example-benchmark-1.0-SNAPSHOT.jar --help
```

适用场景：

- 想测试更接近纯 RPC 的性能。
- 不想把 HTTP Controller 的开销混入结果。
- 想在命令行中执行压测。

## 10. 云服务器部署建议

推荐方式：

```text
云服务器：ZooKeeper + example-provider
本地电脑：example-consumer + 压测控制台
连接方式：SSH 隧道
```

推荐只开放云服务器 `22` 端口，通过 SSH 隧道访问：

```powershell
ssh -N -L 12181:127.0.0.1:2181 -L 19090:127.0.0.1:19090 -L 18080:127.0.0.1:18080 root@你的服务器IP
```

本地 consumer 连接：

```powershell
java -jar .\example-consumer\target\example-consumer-1.0-SNAPSHOT.jar --rpc.registry.address=127.0.0.1:12181 --server.port=18081
```

完整步骤见：

```text
doc/05-testing/cloud-provider-local-consumer.md
```

## 11. 常见问题

### 11.1 `java -jar` 提示没有主清单属性

原因：jar 不是 Spring Boot 可执行 jar。

解决：

```powershell
mvn -pl example-provider -am clean package -DskipTests
```

确认存在 `BOOT-INF/`：

```powershell
jar tf .\example-provider\target\example-provider-1.0-SNAPSHOT.jar | Select-String -Pattern "^BOOT-INF/" | Select-Object -First 5
```

### 11.2 `UnsupportedClassVersionError`

原因：服务器 Java 版本过低。

解决：

```bash
java -version
```

如果是 Java 8，安装并切换到 Java 11/16/17。

### 11.3 consumer 找不到 provider

检查：

1. ZooKeeper 是否启动。
2. provider 是否已经启动并注册服务。
3. consumer 和 provider 的 `rpc.registry.address` 是否指向同一个 ZooKeeper。
4. `rpc.server.host` 注册出来的地址，consumer 是否能访问。

### 11.4 端口被占用

Windows：

```powershell
netstat -ano | findstr :18081
```

Linux：

```bash
ss -lntp | grep -E '18080|18081|19090|2181'
```

### 11.5 `spring-boot:run` 找不到插件

使用完整插件坐标：

```powershell
mvn -pl example-consumer -am org.springframework.boot:spring-boot-maven-plugin:2.7.18:run
```

或者直接打包后使用 `java -jar`。

### 11.6 熔断测试 provider 启动不起来

熔断参数是 consumer 参数，不要加到 provider 上。

provider 普通启动：

```bash
java -jar example-provider-1.0-SNAPSHOT.jar \
  --rpc.registry.address=127.0.0.1:2181 \
  --rpc.server.host=127.0.0.1 \
  --rpc.server.port=19090 \
  --server.port=18080
```

consumer 才加熔断参数。

## 12. 推荐使用顺序

第一次使用：

1. 启动 ZooKeeper。
2. 启动 provider。
3. 启动 consumer。
4. 打开 consumer 控制台。
5. 查看 `/rpc/observability`。
6. 跑一轮 hello 压测。

准备演示：

1. 使用 jar 启动 provider 和 consumer。
2. 打开压测控制台。
3. 展示 1、5、10、20 线程压测结果。
4. 展示 provider 限流。
5. 展示 consumer 熔断。
6. 打开最终测试结论文档说明边界。

继续开发：

1. 在 api 模块新增接口。
2. 在 provider 模块实现接口并加 `@RpcService`。
3. 在 consumer 模块用 `@RpcReference` 注入接口。
4. 添加或调整配置。
5. 运行本地示例。
6. 补充测试记录。
