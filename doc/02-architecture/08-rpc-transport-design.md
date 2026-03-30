# RPC 传输层设计详解

## 1. 文档目的

这份文档解释当前项目的传输层（transport，传输层）是怎么设计的，以及为什么会同时保留 `netty` 和 `socket` 两种实现。

重点包括：

1. transport 在框架里到底负责什么
2. 为什么要把 transport 和 invocation / registry / protocol 分开
3. Netty 客户端和服务端分别怎么工作
4. Socket 实现为什么仍然值得保留
5. 心跳、重连、连接池、请求管理器分别位于什么位置

建议配合这些类一起阅读：

1. `RpcTransport`
2. `RpcServer`
3. `RpcTransportFactory`
4. `RpcServerFactory`
5. `RpcNettyClient`
6. `ConnectionPool`
7. `RpcConnection`
8. `RequestManager`
9. `RpcClientHandler`
10. `ReconnectHandler`
11. `HeartbeatHandler`
12. `RpcNettyServer`
13. `RpcSocketClient`
14. `RpcSocketServer`

---

## 2. transport 到底负责什么

当前 transport 层只负责下面这些事情：

1. 建立连接
2. 发送消息
3. 接收消息
4. 维护连接状态
5. 调用协议编解码
6. 心跳
7. 重连

它不应该负责下面这些事情：

1. 服务发现
2. 负载均衡
3. 重试策略
4. 熔断与降级决策
5. 方法级配置解析

这条边界很关键。  
因为一旦 transport 承担太多治理逻辑，后面很快又会变回“超级大类”。

---

## 3. 为什么要和调用编排层分开

调用编排层更关心：

1. 这次调用打给谁
2. 要不要重试
3. 要不要降级
4. 当前方法级配置是什么

transport 更关心：

1. 连接怎么建立
2. 数据怎么发出去
3. 响应怎么回来

所以当前项目把：

1. `invoke（调用编排层）`
2. `transport（传输层）`

明确拆成两层。

这样 transport 才能真正做到：

- 上层编排一致，底层发送方式可替换

---

## 4. transport 抽象层

### 4.1 RpcTransport

这是客户端发送抽象。  
它的核心职责是提供：

“给一个 `RpcRequest`，返回一个 `RpcResponse`”

的能力。

至于底层实现是：

1. `netty`
2. `socket`

都不应该影响上层调用链。

### 4.2 RpcServer

这是服务端抽象。  
它的职责是：

1. 启动服务端监听
2. 关闭服务端
3. 访问本地注册表

### 4.3 工厂层

当前通过：

1. `RpcTransportFactory`
2. `RpcServerFactory`

按配置决定到底创建：

1. Netty 实现
2. Socket 实现

这就是“传输实现选择下沉到工厂层”的意义。

---

## 5. 为什么同时保留 Netty 和 Socket

很多时候会有人问：既然 Netty 更完整，为什么不直接删掉 Socket。

当前保留两者是有明确价值的。

### 5.1 Netty 的价值

Netty 更适合真实 RPC 运行场景，因为它支持：

1. 长连接
2. 请求复用
3. 连接池
4. 心跳
5. 重连
6. 异步处理

### 5.2 Socket 的价值

Socket 版更适合：

1. 理解最小传输实现
2. 快速验证协议模型
3. 作为集成测试对照实现

### 5.3 保留两种实现的真正意义

不是为了重复造轮子，而是为了证明：

上层调用编排已经不依赖具体网络框架。

---

## 6. Netty 客户端整体结构

当前 `RpcNettyClient` 是较完整的客户端实现，内部最关键的组件包括：

1. `ConnectionPool`
2. `RpcConnection`
3. `RequestManager`
4. `RpcClientHandler`
5. `HeartbeatHandler`
6. `ReconnectHandler`
7. `RpcClientInvocationExecutor`

其中需要注意：

`RpcClientInvocationExecutor` 虽然被 Netty 客户端持有，但它更接近“调用编排和 transport 之间的桥接层”，不属于纯底层收发实现。

---

## 7. Netty 客户端：连接池

### 7.1 为什么需要 ConnectionPool

如果每次请求都重新建立一个 Netty 连接，会有明显问题：

1. 开销大
2. 无法充分利用长连接
3. 心跳和重连体系失去意义

所以当前按地址维度维护连接池。

### 7.2 连接池按什么维度复用

当前主要按：

`host:port`

复用 `channel（网络通道）`。

也就是说，同一个 provider 地址的请求会优先复用已有连接。

### 7.3 RpcConnection 的意义

`RpcConnection` 本质上是：

- 对单个 Netty channel 的语义封装

它承载：

1. 地址信息
2. channel 状态
3. 连接对象语义

---

## 8. Netty 客户端：RequestManager

### 8.1 为什么异步 transport 必须有请求管理器

Netty 是异步模型，请求发出去时：

1. 当前线程不会立刻拿到返回值
2. 响应可能在未来某个时刻到达

所以必须有一个中间管理器维护：

`requestId（请求标识） -> future（异步结果占位）`

### 8.2 它负责什么

1. 注册等待中的请求
2. 收到响应时找到对应 future
3. 完成 future
4. 清理已结束请求

### 8.3 requestId 为什么关键

因为它是：

- 客户端请求和服务端响应之间的唯一关联键

没有它，请求复用就无法可靠工作。

---

## 9. Netty 客户端：RpcClientHandler

`RpcClientHandler` 是客户端接收响应的核心处理器。  
它主要负责：

1. 区分消息类型
2. 识别业务响应和心跳响应
3. 把业务响应交给 `RequestManager`

它不负责重试、降级等治理决策，因为那属于更高层。

---

## 10. Netty 客户端：心跳

### 10.1 为什么要有 HeartbeatHandler

长连接如果长期空闲，需要有机制判断连接是否还活着。  
否则就可能出现：

1. 连接已经断开但本地没感知
2. 真正发请求时才发现连接已死

### 10.2 当前心跳思路

当前心跳主要依赖：

1. 空闲检测
2. 心跳消息发送
3. 心跳响应

### 10.3 为什么通常在写空闲时发心跳

因为一段时间没有任何请求写出时，最容易无法确认连接活性。  
所以在写空闲时主动发心跳是合理策略。

---

## 11. Netty 客户端：重连

### 11.1 为什么需要 ReconnectHandler

长连接场景下，连接断开并不代表整个客户端应该退出。  
很多时候需要自动恢复连接。

### 11.2 当前重连机制做什么

主要包括：

1. 监听连接断开
2. 判断是否允许自动重连
3. 计算下一次重连延迟
4. 支持最大重试次数
5. 支持抖动（jitter，随机扰动）

### 11.3 为什么要区分“主动关闭”和“异常断线”

如果不区分，就会出现：

1. 客户端自己正常退出时还在重连

这个问题前面已经修过，所以现在关闭客户端时会带上关闭状态，避免把正常停机误判成异常断线。

---

## 12. Netty 服务端整体结构

当前 `RpcNettyServer` 的核心结构包括：

1. Netty server bootstrap
2. pipeline（处理流水线）
3. `RpcRequestHandler`
4. `RpcRequestDispatcher`
5. `RpcRequestExecutor`
6. 业务线程池
7. `ServerLifecycle`

它和客户端一样，也是把：

1. 网络收发
2. 请求分发
3. 业务执行

明确拆层。

---

## 13. Netty 服务端：分发与执行为什么分离

### 13.1 RpcRequestDispatcher 的职责

它主要负责：

1. 区分心跳和业务请求
2. 判断停机状态
3. 把业务请求转交给执行器

### 13.2 RpcRequestExecutor 的职责

它主要负责：

1. 进入业务线程池
2. 维护 inflight（执行中）请求计数
3. 执行 provider 过滤器链
4. 执行反射调用
5. 组装响应

这样做可以避免把网络线程和业务线程混在一起。

---

## 14. Socket 传输实现

`RpcSocketClient` 和 `RpcSocketServer` 保留的是“更直接、更容易看懂”的传输实现。  
它们的特点是：

1. 模型简单
2. 更适合理解最小收发链路
3. 便于和 Netty 版本做对照

虽然底层实现不同，但它们仍然复用：

1. 同样的协议模型
2. 同样的上层调用编排
3. 同样的工厂切换方式

---

## 15. 小结

当前 transport 层已经形成了比较清晰的结构：

1. 抽象层负责统一客户端与服务端接口
2. 工厂层负责按配置选择具体实现
3. Netty 提供完整的长连接与异步能力
4. Socket 保留最小实现与对照价值
5. 上层调用编排已经和底层传输方式解耦

这也是当前项目能在不改上层调用链的情况下，在 `netty` 和 `socket` 之间切换的根本原因。
