# 常见问题与排障

## 1. 示例启动顺序

1. 确认 ZooKeeper 可用。
2. 启动 `example-provider`。
3. 再启动 `example-consumer`。

consumer 先启动时，如果注册中心里还没有 provider 实例，服务发现或调用阶段可能失败。

推荐使用 JDK 16 或更高版本运行构建。虽然根 POM 里有 Java 11 属性，部分子模块当前显式使用 source/target 16。

## 2. 注册中心地址

示例默认从 `RPC_REGISTRY_ADDRESS` 读取注册中心地址，没有设置时使用配置文件里的默认值。

本地排障建议先明确指定：

```text
RPC_REGISTRY_ADDRESS=127.0.0.1:2181
```

排查顺序：

1. ZooKeeper 进程是否启动。
2. provider 是否能连上 ZooKeeper。
3. consumer 是否和 provider 使用同一个 registry address。
4. provider 是否已经发布服务。

## 3. 端口

示例 Spring Boot Web 端口：

1. provider：`18080`
2. consumer：`18081`

RPC server 端口由 RPC 配置决定，不要和 Spring Boot Web 端口混为一谈。

## 4. consumer 注入为空

优先检查：

1. consumer 应用是否加载了 RPC Spring Boot starter。
2. `rpc.spring.enabled` 是否被关掉。
3. 字段上是否有 `@RpcReference`。
4. 字段类型是否是服务接口。
5. `RpcSpringManager` 是否被自动装配。

## 5. provider 服务找不到

优先检查：

1. provider 实现类是否标注 `@RpcService`。
2. 服务实现类是否被 Spring 扫描到。
3. Spring Boot 主类包路径是否覆盖服务实现类。
4. `rpc.spring.scan-packages` 或 `rpc.server.scan-packages` 是否配置正确。
5. `LocalRegistryImpl` 是否注册了对应 serviceName。

## 6. 请求超时

排查顺序：

1. provider 进程是否存在。
2. consumer 是否能从注册中心发现 provider 地址。
3. Netty client 是否能连接 provider 地址。
4. `RequestManager` 是否登记了 requestId。
5. provider 是否返回了同一个 requestId 的 `RpcResponse`。
6. 方法级 read timeout 是否过短。

## 7. 第一遍不要查的方向

如果你只是想跑通示例，不要先查：

1. SPI 依赖注入细节。
2. legacy socket 实现。
3. 所有序列化器的内部实现。
4. ZooKeeper watcher 的全部边界。

这些适合在主链路跑通后再看。

## 8. 构建失败

优先检查：

1. 当前 `java -version` 是否为 JDK 16+。
2. 是否在仓库根目录执行 Maven 命令。
3. 是否能正常解析 Maven 依赖。
4. 如果只运行单个示例模块，是否带上 `-am` 一起构建依赖模块。

示例：

```bash
mvn clean test
mvn -pl example-provider -am spring-boot:run
mvn -pl example-consumer -am spring-boot:run
```

## 9. 运行状态页面打不开

示例页面：

```text
http://127.0.0.1:18080/rpc/observability/dashboard
http://127.0.0.1:18081/rpc/observability/dashboard
```

优先检查：

1. 应用是否已经启动。
2. `server.port` 是否仍是 `18080` 或 `18081`。
3. 当前应用是否引入 `spring-boot-starter-web`。
4. 是否设置了 `rpc.observability.http.enabled=false`。
5. 如果配置了 `server.servlet.context-path`，访问路径前要加上 context path。

原始 JSON 端点：

```text
/rpc/observability?includeServices=true&limit=200
```
