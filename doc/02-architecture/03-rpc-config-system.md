# RPC 配置系统详解

## 1. 文档目的

这份文档解释当前项目的配置系统是怎么设计的、配置如何被读取和绑定，以及为什么最终拆成现在这种结构。

重点回答：

1. 当前有哪些配置入口
2. 配置最终落到哪些配置对象上
3. `RpcConfigLoader` 为什么要拆层
4. Spring Boot（应用启动框架）场景下配置如何接入
5. 方法级配置是如何生效的

建议配合这些类一起阅读：

1. `RpcFrameworkConfig`
2. `RpcClientConfig`
3. `RpcServerConfig`
4. `RpcConfigLoader`
5. `RpcPropertySource`
6. `RpcFrameworkConfigBinder`
7. `RpcClientConfigBinder`
8. `RpcServerConfigBinder`
9. `RpcFilterConfigBinder`
10. `RpcMethodConfigBinder`
11. `RpcBootFrameworkProperties`

---

## 2. 为什么配置系统必须独立成层

很多自写 RPC 项目一开始都会把配置、对象装配和业务逻辑混在一起，常见表现是：

1. 启动代码里到处 `new`
2. 到处散落硬编码参数
3. 新增一个配置要改很多地方
4. Spring 和非 Spring 场景各写一套逻辑

这样会直接带来几个问题：

1. 外部接入成本高
2. 配置优先级难讲清楚
3. 代码会越来越散
4. 后续重构风险很大

所以当前项目把配置系统单独收成一层。  
它的职责不是“发请求”，而是“把外部输入转换成框架内部统一理解的配置模型”。

---

## 3. 当前有哪些配置入口

当前主要有三类配置入口。

### 3.1 `rpc.properties`

这是 core（核心模块）层默认的配置入口，适合：

1. 非 Spring Boot 场景
2. 提供框架默认值

优点是简单直接，框架可以独立工作。  
局限是结构表达能力不如 Spring Boot 原生绑定自然。

### 3.2 JVM 系统属性

`RpcPropertySource` 支持系统属性覆盖，所以很多配置都可以通过：

```bash
-Drpc.xxx=...
```

来临时覆盖。

这主要是为了：

1. 本地调试方便
2. 启动脚本覆盖方便
3. 兼容非 Spring Boot 场景

### 3.3 Spring Boot 配置文件

在 Spring Boot 场景下，更推荐直接使用：

1. `application.yml`
2. `application.properties`

这时配置会先绑定到 `RpcBootFrameworkProperties`，再转换成 `RpcFrameworkConfig`。

---

## 4. 配置对象分层

当前配置对象不是一个“大对象包打天下”，而是有层次地拆开。

### 4.1 `RpcFrameworkConfig`

这是高层统一配置对象，可以理解成“框架总配置”。  
它承载的内容比较全，包括：

1. `transport（传输方式）`
2. `serializer（序列化器）`
3. `load balancer（负载均衡器）`
4. `registry（注册中心）`
5. `server（服务端）` 配置
6. `client（客户端）` 配置
7. `filter（过滤器）` 配置
8. 方法级配置

它最适合被 bootstrap（启动器）和上层装配逻辑使用。

### 4.2 `RpcClientConfig`

这是更贴近消费端执行链路的配置对象。  
它主要影响：

1. 传输客户端
2. 调用执行器
3. 重试、熔断、限流等治理能力

所以可以把它理解成“运行期消费端配置”。

### 4.3 `RpcServerConfig`

这是更贴近服务端执行链路的配置对象。  
它主要影响：

1. 监听地址与端口
2. `boss/worker` 线程
3. 业务线程池
4. 空闲连接控制
5. 优雅停机超时

---

## 5. 配置加载链路

在非 Boot 场景下，配置加载流程大致是：

1. `RpcConfigLoader.load()`
2. 创建 `RpcPropertySource`
3. 调用 `RpcFrameworkConfigBinder`
4. 再分发到各个领域 binder（绑定器）
5. 得到 `RpcFrameworkConfig`
6. bootstrap 再把它转换成 client/server 运行对象

### 5.1 `RpcConfigLoader`

它现在的职责已经被收窄。  
它主要负责：

1. 加载原始 `properties（属性文件）`
2. 创建 `RpcPropertySource`
3. 交给 binder（绑定器）体系做真正绑定

它不再负责自己解析所有配置键。

### 5.2 `RpcPropertySource`

它是配置读取层，只负责：

1. 取值
2. 处理系统属性覆盖
3. 转基础类型
4. 读列表
5. 读前缀映射

它只负责“读值”，不负责“理解值的业务意义”。

### 5.3 `RpcFrameworkConfigBinder`

这是总绑定入口，负责把不同领域的绑定结果收口成 `RpcFrameworkConfig`。

它不应该再退化成“大而全的解析器”，职责应该始终保持在：

1. 调用各个子 binder
2. 汇总结果

---

## 6. 为什么继续拆成多个 Binder

后面继续把绑定逻辑拆成多个 binder，是因为不同配置域的职责完全不同。

当前主要包括：

1. `RpcClientConfigBinder`
2. `RpcServerConfigBinder`
3. `RpcFilterConfigBinder`
4. `RpcMethodConfigBinder`

### 6.1 `RpcClientConfigBinder`

负责消费端配置，例如：

1. 连接超时
2. 读取超时
3. `retry（重试）`
4. `cluster（集群容错）`
5. `reconnect（重连）`
6. `discovery（服务发现）`
7. 消费端治理配置

### 6.2 `RpcServerConfigBinder`

负责服务端配置，例如：

1. 主机与端口
2. 线程池
3. 优雅停机
4. 服务端治理配置

### 6.3 `RpcFilterConfigBinder`

负责过滤器相关配置，例如：

1. `consumer filter（消费端过滤器）` 列表
2. `invoker filter（调用执行器过滤器）` 列表
3. `provider filter（服务端过滤器）` 列表
4. 过滤器顺序覆盖

### 6.4 `RpcMethodConfigBinder`

负责方法级配置，把“全局配置”和“单方法覆盖”这两层结构拆开。

---

## 7. 方法级配置系统

### 7.1 为什么它很重要

如果一个 RPC 框架只有全局配置，很快就会不够用。  
实际服务里经常出现：

1. 某个方法超时要更短
2. 某个方法不允许自动重试
3. 某个方法需要单独限流
4. 某个方法需要按方法维度熔断

所以当前项目已经支持方法级配置。

### 7.2 当前可配置的内容

当前主要包括：

1. `retryTimes（重试次数）`
2. `clusterStrategy（集群策略）`
3. `readTimeout（读取超时）`
4. `serializerName（序列化器名称）`
5. `loadBalancerName（负载均衡器名称）`
6. `rateLimitEnabled（限流开关）`
7. `rateLimitPermitsPerSecond（每秒许可数）`
8. `circuitBreakerScope（熔断作用域）`

这些配置最终会在调用时解析成 `InvocationOptions`。

---

## 8. Filter 配置为什么单独成层

过滤器配置和普通 server/client 配置不同。  
它不是简单的一个数值，而是“名字列表 + 顺序覆盖”的组合。

因此当前单独抽出 `RpcFilterConfigBinder`，专门处理：

1. 哪些过滤器启用
2. 哪些过滤器属于哪个阶段
3. 顺序如何覆盖

这样过滤器体系才不会重新变成硬编码。

---

## 9. Spring Boot 绑定路径

在 Spring Boot 场景下，配置链路会变成：

1. `application.yml`
2. `RpcBootFrameworkProperties`
3. 转换为 `RpcFrameworkConfig`
4. 被 `RpcSpringBootAutoConfiguration` 和 `RpcSpringManager` 使用

这样可以让外部使用者直接写 Boot 原生配置，而不需要手工调用 `RpcConfigLoader`。

---

## 10. 当前设计的好处

把配置系统拆成现在这种结构后，有几个明显收益：

1. 外部接入方式统一
2. 非 Boot 和 Boot 场景都能兼容
3. 新增配置时修改范围更可控
4. 方法级配置更容易落地
5. 配置职责和运行职责边界更清楚

---

## 11. 建议的源码阅读顺序

如果你要顺着配置系统读源码，建议按这个顺序：

1. `RpcConfigLoader`
2. `RpcPropertySource`
3. `RpcFrameworkConfigBinder`
4. `RpcClientConfigBinder`
5. `RpcServerConfigBinder`
6. `RpcFilterConfigBinder`
7. `RpcMethodConfigBinder`
8. `RpcFrameworkConfig`
9. `RpcClientConfig`
10. `RpcBootFrameworkProperties`

---

## 12. 小结

当前配置系统已经形成了比较明确的分层：

1. 配置入口层负责接收外部输入
2. 读取层负责统一取值与覆盖
3. 绑定层负责把配置映射成领域对象
4. bootstrap（启动器）层负责消费这些配置

这让后续继续扩展新配置项、方法级治理配置或 Spring Boot 绑定时，不需要重新打散整个配置系统。
