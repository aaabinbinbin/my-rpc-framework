# 第 7 课：Netty 客户端实现

## 学习目标

- 掌握 Netty 客户端的启动流程
- 学会管理 Channel 连接池
- 实现异步请求和 Future/Promise 模式
- 理解同步转异步的处理方式

---

## 一、RPC 客户端架构设计

### 1.1 客户端整体架构

```
┌─────────────────────────────────────────────────────────┐
│                   RPC Client                            │
├─────────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────────┐  │
│  │              Proxy Layer (代理层)                 │  │
│  │  - JDK/CGLIB 动态代理                             │  │
│  │  - 拦截方法调用                                    │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↓                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │           Request Manager (请求管理器)             │  │
│  │  - 生成请求 ID                                     │  │
│  │  - 存储 Pending 请求                                │  │
│  │  - 匹配响应                                        │  │
│  └───────────────────────────────────────────────────┘  │
│                          ↓                               │
│  ┌───────────────────────────────────────────────────┐  │
│  │            Netty Network Layer                    │  │
│  │  - Connection Pool (连接池)                       │  │
│  │  - Decoder/Encoder (编解码器)                     │  │
│  │  - Handler (处理器)                               │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 1.2 核心挑战

1. **同步转异步**：用户调用是同步的，但网络 IO 是异步的
2. **请求响应匹配**：如何匹配响应和请求？
3. **连接管理**：长连接 vs 短连接？连接池如何设计？
4. **超时处理**：如何处理长时间未响应的请求？

---

## 二、RPC 远程调用完整流程详解

### 2.1 整体调用流程概览

一次完整的 RPC调用涉及多个组件的协作，让我们详细拆解每个步骤。

```
┌─────────────────────────────────────────────────────────┐
│                    RPC调用完整流程                      │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  1. 客户端初始化                                        │
│     - 创建 RpcClient（还未建立连接）                     │
│     - 初始化连接池（空的）                               │
│                                                         │
│  2. 创建代理对象                                        │
│     - proxyFactory.createProxy(HelloService.class)      │
│     - 返回 JDK 动态代理对象                              │
│                                                         │
│  3. 调用代理方法                                        │
│     - proxy.sayHello("张三")                            │
│     - 进入 InvocationHandler.invoke()                   │
│                                                         │
│  4. 构建请求                                            │
│     - RpcRequest(serviceName, methodName, params)       │
│     - 生成唯一的 requestId                              │
│                                                         │
│  5. 创建 CompletableFuture                             │
│     - future = new CompletableFuture<>()                │
│     - RequestManager.put(requestId, future)             │
│                                                         │
│  6. 获取连接（懒加载）                                  │
│     - channel = pool.get(ip:port)                       │
│     - 如果没有，创建新连接并放入池中                     │
│                                                         │
│  7. 发送请求                                            │
│     - channel.writeAndFlush(request)                    │
│     - 触发 Pipeline 出站：encode → idle → write         │
│                                                         │
│  8. 阻塞等待响应                                        │
│     - future.get(5000, MILLISECONDS)                    │
│     - 线程挂起，但不占用 CPU                            │
│                                                         │
│  9. 服务端接收                                          │
│     - SocketChannel.read() → ByteBuf                    │
│     - Pipeline 入站：decode → handler                   │
│                                                         │
│  10. 业务处理                                           │
│      - 反射调用：service.sayHello("张三")               │
│      - 得到结果："Hello, 张三!"                          │
│                                                         │
│  11. 返回响应                                           │
│      - 构建 RpcResponse(result)                         │
│      - writeAndFlush(response)                          │
│                                                         │
│  12. 客户端接收响应                                     │
│      - channelRead0() 收到响应                          │
│      - 从 requestId 找到对应的 future                    │
│                                                         │
│  13. 唤醒阻塞线程                                       │
│      - future.complete(response)                        │
│      - 步骤 8 的线程被唤醒                               │
│                                                         │
│  14. 返回结果                                           │
│      - future.get() 返回 RpcResponse                    │
│      - 解析得到：result = "Hello, 张三!"                 │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 2.2 详细的时序图

```
客户端线程                      RpcClient                    Netty Channel                  网络              服务端
   │                              │                              │                            │                │
   │ 1. proxy.sayHello("张三")     │                              │                            │                │
   │ ───────────────────────────> │                              │                            │                │
   │                              │                              │                            │                │
   │                              │ 2. 构建 RpcRequest            │                            │                │
   │                              │    (serviceName, methodName) │                            │                │
   │                              │                              │                            │                │
   │                              │ 3. 创建 CompletableFuture    │                            │                │
   │                              │    future = new Future()     │                            │                │
   │                              │                              │                            │                │
   │                              │ 4. 保存映射关系              │                            │                │
   │                              │    RequestManager.put(id, f) │                            │                │
   │                              │                              │                            │                │
   │                              │ 5. writeAndFlush(request)    │                            │                │
   │                              │ ───────────────────────────> │                            │                │
   │                              │                              │ 6. 编码 (RpcMessage→ByteBuf)│               │
   │                              │                              │ ──────────────────────────> │                │
   │                              │                              │                            │ 7. TCP 传输    │
   │                              │                              │                            │ ────────────> │
   │                              │                              │                            │                │ 8. 解码
   │                              │                              │                            │                │ (ByteBuf→RpcMessage)
   │                              │                              │                            │                │ ────────>
   │                              │                              │                            │                │
   │                              │                              │                            │                │ 9. handler.channelRead()
   │                              │                              │                            │                │    反射调用 sayHello()
   │                              │                              │                            │                │
   │                              │                              │                            │                │ 10. 构建响应
   │                              │                              │                            │                │     response = "Hello, 张三!"
   │                              │                              │                            │                │
   │                              │                              │                            │                │ 11. writeAndFlush(response)
   │                              │                              │                            │ <───────────── │
   │                              │                              │ <───────────────────────── │                │
   │                              │ <─────────────────────────── │                            │                │
   │                              │                              │                            │                │
   │                              │ 12. 解码响应                 │                            │                │
   │                              │    从 Header 获取 requestId   │                            │                │
   │                              │                              │                            │                │
   │                              │ 13. 查找并唤醒 Future        │                            │                │
   │                              │    future = remove(requestId)│                            │                │
   │                              │    future.complete(response) │                            │                │
   │                              │                              │                            │                │
   │ <─────────────────────────── │                              │                            │                │
   │ 14. future.get() 返回结果    │                              │                            │                │
   │ "Hello, 张三!"                │                              │                            │                │
```

### 2.3 关键组件详解

#### **1️⃣ 连接池的懒加载机制**

**错误理解：** 启动就创建所有连接

**正确实现：** 第一次调用时才创建连接

```java
public class ConnectionPool {
    private final Map<String, RpcConnection> connectionMap = new ConcurrentHashMap<>();
    
    /**
     * 获取连接（懒加载）
     */
    public RpcConnection getConnection(String host, int port) throws Exception {
        String key = buildKey(host, port);
        
        // 1. 尝试获取已有连接
        RpcConnection connection = connectionMap.get(key);
        
        if (connection != null && connection.isActive()) {
            log.debug("复用已有连接：{}", key);
            connection.updateLastUseTime();
            return connection;
        }
        
        // 2. 【懒加载】没有连接时才创建新连接
        log.info("创建新连接：{}", key);
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port)).sync();
        
        Channel channel = future.channel();
        RpcConnection newConnection = new RpcConnection(channel, host, port);
        
        connectionMap.put(key, newConnection);
        
        return newConnection;
    }
}
```

**优势：**
- ✅ 避免资源浪费（不用的服务不创建连接）
- ✅ 启动速度快
- ✅ 按需扩展

---

#### **2️⃣ "写消息"的真实过程**

**表面理解：** 直接向 Channel 写入字节

**深层真相：** 触发 Pipeline 的出站事件链

```java
// 应用层代码
channel.writeAndFlush(rpcMessage);

// ↓↓↓ 底层发生的事情 ↓↓↓

// 1. 触发 ChannelOutboundHandler 的 write() 方法
// 2. 按从后往前的顺序执行 Pipeline 中的所有 OutboundHandler

【Pipeline 出站流程】
┌─────────────────────────────────────────────────────────┐
│  Handler                          │  事件               │
├─────────────────────────────────────────────────────────┤
│  RpcClientHandler                 │  writeAndFlush()   │
│       ↓                           │  ↓                  │
│  RpcProtocolEncoder               │  write(msg)        │
│       │                           │  ├─ 检查类型        │
│       │                           │  ├─ encode() 编码   │
│       │                           │  └─ write(ByteBuf) │
│       ↓                           │  ↓                  │
│  IdleStateHandler                 │  write(buf)        │
│       │                           │  └─ 更新空闲时间    │
│       ↓                           │  ↓                  │
│  LoggingHandler                   │  write(buf)        │
│       │                           │  └─ 记录日志        │
│       ↓                           │  ↓                  │
│  HeadContext                      │  write(buf)        │
│                                   │  └─ 调用 NIO write  │
└─────────────────────────────────────────────────────────┘
```

**关键点：**
- ✅ `writeAndFlush()`不是直接写字节
- ✅ 而是触发一系列**出站事件**
- ✅ 每个 OutboundHandler 都可以修改或拦截消息
- ✅ 编码器自动将 `RpcMessage`转成`ByteBuf`

---

#### **3️⃣ CompletableFuture 的阻塞机制**

**问题：** CompletableFuture 是阻塞还是非阻塞？

**答案：** 是**可超时的智能阻塞**

```java
// 方式 1：传统阻塞（傻等）
public Object call() {
    channel.writeAndFlush(request);
    
    // 无限阻塞，直到等到结果
    RpcResponse response = responseQueue.take();  // ← 死等
    
    return response.getResult();
}

// 方式 2：CompletableFuture（智能阻塞）
public Object call() {
    // 1. 创建 Future 对象
    CompletableFuture<RpcResponse> future = new CompletableFuture<>();
    
    // 2. 保存 requestId 和 future 的映射关系
    RequestManager.setRequestFuture(requestId, future);
    
    // 3. 发送请求
    channel.writeAndFlush(request);
    
    // 4. 【关键】阻塞等待，但可以设置超时
    RpcResponse response = future.get(5000, TimeUnit.MILLISECONDS);
    //       ↑ 这里会阻塞，但最多 5 秒
    
    return response.getResult();
}

// 服务端响应到达时
@Override
protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
    RpcResponse response = (RpcResponse) message.getBody();
    
    // 从管理器中取出对应的 future
    CompletableFuture<RpcResponse> future = 
        RequestManager.removeFuture(response.getRequestId());
    
    // 唤醒阻塞的线程
    if (future != null) {
        future.complete(response);  // ← 设置结果并唤醒
    }
}
```

**三种阻塞方式对比：**

| 方式 | 阻塞类型 | 优点 | 缺点 |
|------|---------|------|------|
| `Object.wait()` | 无限阻塞 | 简单 | 可能永远等不到 |
| `Queue.take()` | 无限阻塞 | 线程安全 | 无法设置超时 |
| `Future.get(timeout)` | **可超时阻塞** | 可控、安全 | 需要管理映射关系 |

**结论：** CompletableFuture 是**可超时的阻塞**，比传统阻塞更安全。

---

#### **4️⃣ RequestManager 的请求 - 响应匹配**

**核心问题：** 如何确保响应能正确匹配到请求？

**解决方案：** 使用 `requestId`作为唯一标识

```java
public class RequestManager {
    // 存储 requestId 和 CompletableFuture 的映射
    private static final Map<Long, CompletableFuture<RpcResponse>> 
        FUTURE_MAP = new ConcurrentHashMap<>();
    
    /**
     * 发送请求前保存 Future
     */
    public static void setRequestFuture(Long requestId, 
                                       CompletableFuture<RpcResponse> future) {
        FUTURE_MAP.put(requestId, future);
    }
    
    /**
     * 收到响应时取出并删除 Future
     */
    public static CompletableFuture<RpcResponse> 
        removeFuture(Long requestId) {
        return FUTURE_MAP.remove(requestId);  // ← 删除避免内存泄漏
    }
    
    /**
     * 超时清理（可选）
     */
    @Scheduled(fixedRate = 1000)  // 每秒执行一次
    public void cleanTimeoutFutures() {
        long now = System.currentTimeMillis();
        FUTURE_MAP.entrySet().removeIf(entry -> {
            // 如果超过 5 秒还没响应，删除
            return now - entry.getValue().createTime > 5000;
        });
    }
}
```

**匹配流程：**

```
发送请求时：
  requestId = 12345
  future = new CompletableFuture<>()
  FUTURE_MAP.put(12345, future)
  
  ↓
  
收到响应时：
  response.requestId = 12345
  future = FUTURE_MAP.remove(12345)  ← 根据 requestId 找到
  future.complete(response)          ← 唤醒等待的线程
```

**关键点：**
- ✅ `requestId`必须全局唯一（使用时间戳 + 自增 ID）
- ✅ 使用`ConcurrentHashMap`保证线程安全
- ✅ 收到响应后立即删除，避免内存泄漏

---

### 2.4 常见的理解误区

#### ❌ **误区 1：连接池的误解**

**错误想法：**
> "启动时就创建所有可能的连接"

**正确做法：**
> "第一次调用某个服务时才创建连接"

**原因：**
- 系统可能有 100 个服务，但实际只用到 10 个
- 启动就创建会浪费 90 个连接的资源和时间

---

#### ❌ **误区 2：写消息的机制**

**错误想法：**
> "向 Channel 写入数据就是直接发送字节"

**正确理解：**
> "writeAndFlush() 触发的是事件，不是直接写字节"

**证据：**
```java
// 如果是直接写字节，应该是这样：
channel.write(byte[]);  // × 但 Netty 不是这样

// 实际上是这样：
channel.write(Object);  // ✓ 传递的是对象
// 然后由 Encoder 自动编码成 ByteBuf
```

---

#### ❌ **误区 3：CompletableFuture 的阻塞**

**错误想法：**
> "要么完全阻塞，要么完全不阻塞"

**正确理解：**
> "可以设置超时时间的智能阻塞"

**对比：**
```java
// 传统阻塞：傻等
Object result = queue.take();  // 永远等下去

// CompletableFuture：可超时
Object result = future.get(5000, MILLISECONDS);  // 最多等 5 秒

// 完全异步：不阻塞
future.thenAccept(result -> {
    // 回调函数，响应到达时自动执行
});
```

---

### 2.5 完整的状态流转图

```
┌──────────────────────────────────────────────────────────────┐
│                      RPC调用状态机                          │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  [初始状态]                                                  │
│       ↓                                                      │
│  1. 创建代理对象                                             │
│       ↓                                                      │
│  2. 调用代理方法                                             │
│       ↓                                                      │
│  3. 构建 RpcRequest                                          │
│       ↓                                                      │
│  4. 创建 CompletableFuture（Pending 状态）                    │
│       ↓                                                      │
│  5. 保存到 RequestManager                                    │
│       ↓                                                      │
│  6. 获取/创建连接                                            │
│       ↓                                                      │
│  7. 发送请求（Sending 状态）                                 │
│       ↓                                                      │
│  8. 阻塞等待（Waiting 状态）← 线程挂起                       │
│       ↓                                                      │
│  ════════════════════════════════════════════                │
│  ║  【服务端处理】                                     ║     │
│  ║  9.  接收请求                                      ║     │
│  ║  10. 解码请求                                      ║     │
│  ║  11. 反射调用业务方法                              ║     │
│  ║  12. 编码响应                                      ║     │
│  ║  13. 返回响应                                      ║     │
│  ════════════════════════════════════════════                │
│       ↓                                                      │
│  14. 收到响应（Received 状态）                               │
│       ↓                                                      │
│  15. 从 RequestManager 移除                                  │
│       ↓                                                      │
│  16. future.complete(response)（Completed 状态）             │
│       ↓                                                      │
│  17. 唤醒等待线程                                            │
│       ↓                                                      │
│  18. 返回结果（Done 状态）                                   │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

### 2.6 性能优化要点

#### **1️⃣ 连接复用**
```java
// 好的做法：复用连接
for (int i = 0; i < 100; i++) {
    RpcConnection conn = pool.getConnection(host, port);
    conn.getChannel().writeAndFlush(request);
}
// 只创建 1 个连接，复用 100 次

// 不好的做法：每次都创建新连接
for (int i = 0; i < 100; i++) {
    Channel ch = bootstrap.connect(host, port).sync().channel();
    ch.writeAndFlush(request);
    ch.close();
}
// 创建 100 次连接，浪费资源
```

#### **2️⃣ 批量请求**
```java
// 串行调用（慢）
Result1 = proxy.method1();  // 100ms
Result2 = proxy.method2();  // 100ms
Result3 = proxy.method3();  // 100ms
// 总耗时：300ms

// 并行调用（快）
CompletableFuture<Result1> f1 = asyncProxy.method1();
CompletableFuture<Result2> f2 = asyncProxy.method2();
CompletableFuture<Result3> f3 = asyncProxy.method3();
CompletableFuture.allOf(f1, f2, f3).join();
// 总耗时：100ms
```

#### **3️⃣ 超时控制**
```java
// 没有超时（危险）
response = future.get();  // 可能永远等下去

// 有超时（安全）
try {
    response = future.get(5000, MILLISECONDS);
} catch (TimeoutException e) {
    log.error("请求超时");
    throw new RpcTimeoutException("请求超时");
}
```

---

## 三、channel.writeAndFlush() 方法深度解析

在前面的学习中，我们多次使用了`channel.writeAndFlush(msg)` 方法来发送 RPC 请求。但这个方法是如何触发 Netty Pipeline 的出站流程的？让我们深入源码级别来理解。

### 3.1 方法签名和重载

首先看看这个方法的定义：

```java
// Channel 接口中的方法
public interface Channel extends ChannelOutboundInvoker {
    
    // 1. 简单版本（不指定 Promise）
    ChannelFuture writeAndFlush(Object msg);
    
    // 2. 带 Promise 版本（可以监听结果）
    ChannelFuture writeAndFlush(Object msg, ChannelPromise promise);
}
```

**关键点：**
- ✅ 传入的是 `Object`，不是`byte[]`或`ByteBuf`
- ✅ 返回的是 `ChannelFuture`，可以监听发送结果
- ✅ 这个方法会触发**出站流程**

---

### 3.2 调用链路追踪

让我们追踪一下当你调用 `channel.writeAndFlush(msg)` 时发生了什么：

```java
// 第 1 步：应用层调用
channel.writeAndFlush(rpcMessage);

// ↓ 委托给 AbstractChannel

// 第 2 步：AbstractChannel 的实现
@Override
public ChannelFuture writeAndFlush(Object msg) {
    return tail.writeAndFlush(msg);  
    // ↑ 关键！从 TailContext 开始
}

// 第 3 步：TailContext 的 writeAndFlush
@Override
public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
    invokeWriteAndFlush(msg, promise);
    return promise;
}

// 第 4 步：触发第一个 OutboundHandler
private void invokeWriteAndFlush(Object msg, ChannelPromise promise) {
    // 【关键】获取上一个 OutboundHandler
    AbstractChannelHandlerContext next = findContextOutbound(MASK_WRITE);
    
    // 第 5 步：调用下一个 Handler 的 write 方法
    next.invokeWrite(msg, promise);
    next.invokeFlush(promise);
}
```

**关键发现：**
- ✅ 从**TailContext**开始（Pipeline 的尾部）
- ✅ 向**HeadContext**方向传递（向前传递）
- ✅ 依次调用每个 Handler 的 `write()` 方法

---

### 3.3 Pipeline 的出站执行顺序详解

假设你的 Pipeline 配置是这样的：

```java
ch.pipeline()
    .addLast("idleStateHandler", new IdleStateHandler(...))  // Handler 1
    .addLast("decoder", new RpcProtocolDecoder())            // Handler 2
    .addLast("encoder", new RpcProtocolEncoder())            // Handler 3
    .addLast("handler", new RpcClientHandler(requestManager)); // Handler 4 (当前)
```

**Pipeline 结构：**

```
┌─────────────────────────────────────────────────────────────┐
│                    ChannelPipeline                           │
│                                                              │
│  Head                                    Tail               │
│   ↓                                       ↑                 │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ ← ← ← ← ← ← ← ← 出站方向 (write) ← ← ← ← ← ← ← ← ← ←   │ │
│ │                                                          │ │
│ │ [idleStateHandler] ← [decoder] ← [encoder] ← [handler]  │ │
│ │       ↑                ↑           ↑           ↑         │ │
│ │    Handler 1        Handler 2    Handler 3    Handler 4  │ │
│ │    (最后执行)      (跳过)      (编码)     (从这里开始)   │ │
│ │                                                          │ │
│ │ → → → → → → → → 入站方向 (read) → → → → → → → → → → →   │ │
│ └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

**当你在 `handler`中调用`writeAndFlush(msg)` 时：**

```
步骤 1: handler.writeAndFlush(rpcMessage)
        ↓
步骤 2: 查找前一个 OutboundHandler → encoder
        ↓
步骤 3: encoder.write(rpcMessage, promise)
        ├─ 检查类型：RpcMessage ✓
        ├─ 调用 encode() 编码成 ByteBuf
        └─ ctx.write(byteBuf, promise)  ← 继续传递
        ↓
步骤 4: 查找再前一个 OutboundHandler → decoder
        ↓
步骤 5: decoder.write(byteBuf, promise)
        ├─ MessageToMessageEncoder 的默认实现
        └─ ctx.write(byteBuf, promise)  ← 直接传递
        ↓
步骤 6: 查找再前一个 OutboundHandler → idleStateHandler
        ↓
步骤 7: idleStateHandler.write(byteBuf, promise)
        ├─ 更新最后写入时间
        └─ ctx.write(byteBuf, promise)  ← 继续传递
        ↓
步骤 8: 到达 HeadContext
        ↓
步骤 9: HeadContext.write(byteBuf, promise)
        ├─ 将 ByteBuf 转换为 NIO 的 ByteBuffer
        └─ socketChannel.write(byteBuffer)  ← 真正发送到网络
```

---

### 3.4 为什么是"向前传递"？

这是 Netty 的设计哲学：

```
【入站事件】从前往后（Head → Tail）
网络数据 → Head → Handler1 → Handler2 → ... → Tail

【出站事件】从后往前（Tail → Head）
应用层调用 → Tail → ... → Handler2 → Handler1 → Head → 网络
```

**原因：**
- 入站：数据从网络来，经过层层处理，到达业务逻辑
- 出站：业务逻辑产生数据，经过层层编码，发送到网络

**形象比喻：**

```
入站（读快递）：
快递员 (Head) → 门卫 (Handler1) → 前台 (Handler2) → 你 (Tail/Handler)
                ↓ 拆包          ↓ 检查          ↓ 使用

出站（寄快递）：
你 (Handler) → 前台 (Handler2) → 门卫 (Handler1) → 快递员 (Head)
              ↓ 包装          ↓ 登记          ↓ 发送
```

---

### 3.5 源码级别的执行流程

#### **第 1 步：AbstractChannelHandlerContext.write()**

```java
// AbstractChannelHandlerContext.java
@Override
public ChannelFuture write(Object msg, ChannelPromise promise) {
    // 1. 检查消息是否合法
    Object m = pipeline.touch(msg, this);
    
    // 2. 获取下一个 OutboundHandler
    AbstractChannelHandlerContext next = findContextOutbound(MASK_WRITE);
    
    // 3. 在正确的 EventLoop 中执行
    EventExecutor executor = next.executor();
    if (executor.inEventLoop()) {
        // 如果已经在 EventLoop 中，直接调用
        next.invokeWrite(m, promise);
    } else {
        // 如果不在，提交到 EventLoop 中执行
        promise.setNonCancellation();
        executor.execute(() -> next.invokeWrite(m, promise));
    }
    
    return promise;
}
```

#### **第 2 步：invokeWrite() - 真正调用 Handler**

```java
private void invokeWrite(Object msg, ChannelPromise promise) {
    // 【关键】根据 Handler 的类型调用不同的方法
    if (next.handler() instanceof ChannelOutboundHandler) {
        // 如果是 OutboundHandler，调用 write()
        ((ChannelOutboundHandler) next.handler()).write(next, msg, promise);
    } else {
        // 如果不是，继续向前找
        next.write(msg, promise);
    }
}
```

#### **第 3 步：MessageToByteEncoder.write()**

```java
// MessageToByteEncoder.java (RpcProtocolEncoder 的父类)
@Override
public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) 
        throws Exception {
    ByteBuf buf = null;
    try {
        // 1. 检查消息类型是否匹配泛型
        if (acceptOutboundMessage(msg)) {
            // 2. 分配 ByteBuf
            buf = allocateBuffer(ctx, msg, true);
            
            // 3. 【核心】调用子类的 encode() 方法
            encode(ctx, (I) msg, buf);
            
            // 4. 继续传递
            ctx.write(buf, promise);
        } else {
            // 5. 类型不匹配，直接传递原消息
            ctx.write(msg, promise);
        }
    } finally {
        // 6. 内存管理
        if (buf != null) {
            buf.release();
        }
    }
}
```

#### **第 4 步：HeadContext.write() - 最终发送**

```java
// HeadContext.java (Pipeline 的头部)
@Override
public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) 
        throws Exception {
    // 【关键】最终调用 Java NIO 的 SocketChannel.write()
    unsafe.write(msg, promise);
}

// AbstractUnsafe.java
@Override
public void write(Object msg, ChannelPromise promise) {
    // 1. 检查消息类型
    if (!(msg instanceof ByteBuf)) {
        promise.setFailure(new UnsupportedOperationException(
            "unsupported message type: " + msg.getClass().getName()));
        return;
    }
    
    // 2. 添加到待发送队列
    outboundBuffer.add(msg, promise);
    
    // 3. 刷新到网络
    flush0();
}

private void flush0() {
    // 4. 真正的 NIO write 操作
    int written = javaChannel().write(nioBuffers);
    //    ↑ 这里才是真正发送到网络的地方！
}
```

---

### 3.6 完整的出站流程图

```
┌──────────────────────────────────────────────────────────────────┐
│                  channel.writeAndFlush(rpcMessage)               │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ Step 1: TailContext.writeAndFlush()                              │
│ - 从 Pipeline 尾部开始                                            │
│ - 创建 ChannelPromise                                            │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ Step 2: 查找前一个 OutboundHandler                               │
│ - findContextOutbound(MASK_WRITE)                                │
│ - 找到：RpcProtocolEncoder                                       │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ Step 3: RpcProtocolEncoder.write()                               │
│ - 检查类型：RpcMessage ✓                                         │
│ - 分配 ByteBuf                                                   │
│ - 调用 encode(ctx, rpcMessage, byteBuf)                          │
│   ├─ 序列化 body                                                 │
│   ├─ 计算 CRC32                                                  │
│   └─ 写入 Header + Body 到 byteBuf                               │
│ - ctx.write(byteBuf, promise) ← 继续传递                         │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ Step 4: IdleStateHandler.write()                                 │
│ - 更新最后写入时间：lastWriteTime = now                          │
│ - 重置空闲定时器                                                 │
│ - ctx.write(byteBuf, promise) ← 继续传递                         │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ Step 5: LoggingHandler.write()                                   │
│ - 记录日志："Writing: " + byteBuf.readableBytes() + " bytes"    │
│ - ctx.write(byteBuf, promise) ← 继续传递                         │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ Step 6: HeadContext.write()                                      │
│ - 转换为 NIO ByteBuffer                                          │
│ - 添加到 outboundBuffer                                          │
│ - 调用 flush0()                                                  │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ Step 7: SocketChannel.write()                                    │
│ - javaChannel().write(nioBuffers)                                │
│ - 数据从用户态 → 内核态                                          │
│ - TCP 协议栈处理                                                 │
│ - 网卡驱动发送                                                   │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ Step 8: ChannelFuture 完成                                       │
│ - promise.setSuccess()                                           │
│ - 通知监听器：future.addListener(...)                            │
└──────────────────────────────────────────────────────────────────┘
```

---

### 3.7 为什么编码器能"拦截"消息？

关键在于 `MessageToByteEncoder`的`write()` 方法：

```java
// MessageToByteEncoder.write()
@Override
public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    // 【拦截的关键】在这里检查并转换消息类型
    if (acceptOutboundMessage(msg)) {
        // 类型匹配，进行编码
        ByteBuf buf = allocateBuffer(ctx, msg, true);
        encode(ctx, (I) msg, buf);  // ← 子类实现编码逻辑
        ctx.write(buf, promise);     // ← 传递编码后的 ByteBuf
    } else {
        // 类型不匹配，直接传递原消息
        ctx.write(msg, promise);
    }
}

// acceptOutboundMessage() 的实现
protected boolean acceptOutboundMessage(Object msg) throws Exception {
    // 检查 msg 是否是泛型 I 的类型（如 RpcMessage）
    return matcher.match(msg);
}
```

**拦截机制：**
1. ✅ `write()` 方法会被 Pipeline 自动调用
2. ✅ 在 `write()` 中检查消息类型
3. ✅ 类型匹配就编码成 `ByteBuf`
4. ✅ 传递编码后的`ByteBuf` 给下一个 Handler
5. ✅ 类型不匹配就直接传递原消息

---

### 3.8 常见的理解误区

#### ❌ **误区 1：writeAndFlush 是直接写数据**

**错误想法：**
> "调用 writeAndFlush 就是直接向网络写入字节"

**正确理解：**
> "writeAndFlush 触发的是事件，不是直接写字节"

**证据：**
```java
// 如果是直接写字节，应该是这样：
channel.write(byte[]);  // × 但 Netty 不是这样

// 实际上是这样：
channel.write(Object);  // ✓ 传递的是对象
// 然后由 Encoder 自动编码成 ByteBuf
```

---

#### ❌ **误区 2：所有 Handler 都会处理 write 事件**

**错误想法：**
> "Pipeline 中的所有 Handler 都会被调用"

**正确理解：**
> "只有实现了 ChannelOutboundHandler 接口的 Handler 才会被调用"

**示例：**
```java
// RpcProtocolDecoder extends ByteToMessageDecoder
// ByteToMessageDecoder extends ChannelInboundHandlerAdapter
// 所以它没有重写 write() 方法，直接传递消息

// RpcProtocolEncoder extends MessageToByteEncoder
// MessageToByteEncoder extends ChannelOutboundHandlerAdapter
// 所以它会拦截并处理 write() 事件
```

---

#### ❌ **误区 3：入站和出站是同一条路**

**错误想法：**
> "数据从来时的路回去"

**正确理解：**
> "入站和出站是完全独立的两条链路"

**对比：**
```
入站链路：Head → Handler1 → Handler2 → Handler3 → Tail
出站链路：Tail → Handler3 → Handler2 → Handler1 → Head

虽然经过相同的 Handler，但方向完全相反！
```

---

### 3.9 实战：自定义 OutboundHandler

如果你想自己处理出站消息，可以这样做：

```java
// 方式 1：继承 ChannelOutboundHandlerAdapter
public class MyOutboundHandler extends ChannelOutboundHandlerAdapter {
    
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) 
            throws Exception {
        System.out.println("MyOutboundHandler.write()");
        
        // 可以在这里修改消息
        if (msg instanceof String) {
            String modified = "Modified: " + msg;
            ctx.write(modified, promise);  // 传递修改后的消息
        } else {
            ctx.write(msg, promise);  // 直接传递
        }
    }
    
    @Override
    public void flush(ChannelHandlerContext ctx, ChannelPromise promise) 
            throws Exception {
        System.out.println("MyOutboundHandler.flush()");
        ctx.flush(promise);
    }
}

// 方式 2：继承 MessageToMessageEncoder（更常用）
public class MyStringEncoder extends MessageToMessageEncoder<String> {
    
    @Override
    protected void encode(ChannelHandlerContext ctx, String msg, List<Object> out) 
            throws Exception {
        // 将 String 编码成 ByteBuf
        ByteBuf buf = ctx.alloc().buffer();
        buf.writeBytes(msg.getBytes(StandardCharsets.UTF_8));
        out.add(buf);  // 添加到输出列表
    }
}

// 使用
ch.pipeline()
    .addLast("myEncoder", new MyStringEncoder())
    .addLast("myHandler", new MyOutboundHandler());
```

---

### 3.10 核心要点总结

#### **关键知识点：**

1. ✅ **触发时机**：调用 `channel.writeAndFlush(msg)` 时
2. ✅ **起点**：从 TailContext（Pipeline 尾部）开始
3. ✅ **方向**：向 HeadContext 方向传递（向前）
4. ✅ **执行者**：只有 ChannelOutboundHandler 会被调用
5. ✅ **编码器拦截**：MessageToByteEncoder 的 write() 方法检查并转换类型
6. ✅ **最终发送**：HeadContext 调用 NIO 的 SocketChannel.write()
7. ✅ **类型要求**：最终必须是 ByteBuf 或 FileRegion

#### **记忆口诀：**

> "出站从尾到头传，编码器在中间拦。**
> **类型匹配就转换，最终 Head 发网络。"

---

## 四、Future/Promise 模式

### 2.1 什么是 Future/Promise？

**Future**：代表一个异步操作的结果。  
**Promise**：可以设置结果的 Future。

```java
// Java 自带的 Future 示例
ExecutorService executor = Executors.newSingleThreadExecutor();

// 提交异步任务
Future<String> future = executor.submit(() -> {
    Thread.sleep(1000);
    return "Hello";
});

// 同步获取结果（阻塞）
String result = future.get();  // 阻塞等待结果

// 带超时的获取
String result = future.get(2, TimeUnit.SECONDS);
```

### 2.2 我们的实现方案

我们将使用 `CompletableFuture`（Java 8+）来实现异步转同步。

```java
import java.util.concurrent.CompletableFuture;

// 创建 CompletableFuture
CompletableFuture<String> future = new CompletableFuture<>();

// 异步线程中设置结果
new Thread(() -> {
    // 执行耗时操作
    String result = doSomething();
    // 设置结果（唤醒等待的线程）
    future.complete(result);
}).start();

// 主线程同步获取结果（可选）
try {
    String result = future.get(5, TimeUnit.SECONDS);  // 最多等 5 秒
    System.out.println("结果：" + result);
} catch (TimeoutException e) {
    System.out.println("超时了！");
}
```

---

## 三、请求管理器

### 3.1 待处理请求容器

```java
package com.rpc.client.manager;

import com.rpc.core.protocol.impl.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 请求管理器
 * 管理所有发出但未收到响应的请求
 */
@Slf4j
public class RequestManager {
    
    /**
     * 存储待处理的请求
     * key: requestId
     * value: CompletableFuture（用于接收响应）
     */
    private final Map<Long, CompletableFuture<RpcResponse>> pendingRequests 
        = new ConcurrentHashMap<>();
    
    /**
     * 添加新的请求
     * @param requestId 请求 ID
     * @return CompletableFuture
     */
    public CompletableFuture<RpcResponse> addRequest(long requestId) {
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        
        log.debug("添加请求：requestId={}", requestId);
        return future;
    }
    
    /**
     * 收到响应，完成 Future
     * @param response RPC 响应
     */
    public void completeResponse(RpcResponse response) {
        long requestId = response.getRequestId();
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        
        if (future != null) {
            future.complete(response);
            log.debug("完成请求：requestId={}, code={}", requestId, response.getCode());
        } else {
            log.warn("未找到对应的请求：requestId={}", requestId);
        }
    }
    
    /**
     * 请求失败，异常完成 Future
     * @param requestId 请求 ID
     * @param cause 异常原因
     */
    public void failRequest(long requestId, Throwable cause) {
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        
        if (future != null) {
            future.completeExceptionally(cause);
            log.error("请求失败：requestId={}", requestId, cause);
        }
    }
    
    /**
     * 清理超时的请求
     * @param timeoutMs 超时时间（毫秒）
     */
    public void clearTimeoutRequests(long timeoutMs) {
        // TODO: 可以添加超时检查逻辑
        // 实际项目中可以使用定时任务或时间轮算法
    }
    
    /**
     * 获取待处理请求数量
     */
    public int getPendingCount() {
        return pendingRequests.size();
    }
}
```

---

## 四、Netty 客户端处理器

### 4.1 客户端 Handler

```java
package com.rpc.client.netty.handler;

import com.rpc.client.manager.RequestManager;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.MessageType;
import com.rpc.core.protocol.impl.RpcResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 客户端处理器
 */
@Slf4j
public class RpcClientHandler extends SimpleChannelInboundHandler<RpcMessage> {
    
    private final RequestManager requestManager;
    
    public RpcClientHandler(RequestManager requestManager) {
        this.requestManager = requestManager;
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage message) {
        // 1. 根据消息类型处理
        if (message.getHeader().getMessageType() == MessageType.RESPONSE) {
            handleResponse(message);
        } else if (message.getHeader().getMessageType() == MessageType.HEARTBEAT_RESPONSE) {
            log.debug("收到心跳响应");
        } else {
            log.warn("不支持的消息类型：{}", message.getHeader().getMessageType());
        }
    }
    
    /**
     * 处理响应
     */
    private void handleResponse(RpcMessage message) {
        RpcResponse response = (RpcResponse) message.getBody();
        
        // 通知请求管理器完成 Future
        requestManager.completeResponse(response);
    }
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            
            // 写空闲时，发送心跳
            if (event.state() == IdleState.WRITER_IDLE) {
                log.debug("发送心跳");
                sendHeartbeat(ctx);
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
    
    /**
     * 发送心跳
     */
    private void sendHeartbeat(ChannelHandlerContext ctx) {
        // TODO: 实现心跳消息发送
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("已连接到服务器：{}", ctx.channel().remoteAddress());
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("与服务器断开连接：{}", ctx.channel().remoteAddress());
        
        // 失败所有待处理请求
        // TODO: 实现失败逻辑
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("客户端异常", cause);
        ctx.close();
    }
}
```

---

## 五、连接池管理

### 5.1 单连接封装

```java
package com.rpc.client.connection;

import io.netty.channel.Channel;
import lombok.Data;

/**
 * 连接封装
 */
@Data
public class RpcConnection {
    
    /** Netty Channel */
    private Channel channel;
    
    /** 服务器地址 */
    private String host;
    
    /** 服务器端口 */
    private int port;
    
    /** 最后使用时间 */
    private long lastUseTime;
    
    /** 是否可用 */
    private boolean available;
    
    public RpcConnection(Channel channel, String host, int port) {
        this.channel = channel;
        this.host = host;
        this.port = port;
        this.lastUseTime = System.currentTimeMillis();
        this.available = true;
    }
    
    /**
     * 更新最后使用时间
     */
    public void updateLastUseTime() {
        this.lastUseTime = System.currentTimeMillis();
    }
    
    /**
     * 检查连接是否有效
     */
    public boolean isActive() {
        return channel != null && channel.isActive();
    }
}
```

### 5.2 简单连接池

```java
package com.rpc.client.pool;

import com.rpc.client.connection.RpcConnection;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单的连接池
 * 每个服务器地址维护一个连接
 */
@Slf4j
public class ConnectionPool {
    
    /**
     * 存储连接
     * key: serverAddress (host:port)
     * value: RpcConnection
     */
    private final Map<String, RpcConnection> connectionMap = new ConcurrentHashMap<>();
    
    private final Bootstrap bootstrap;
    
    public ConnectionPool(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }
    
    /**
     * 获取连接（如果没有则创建）
     */
    public RpcConnection getConnection(String host, int port) throws Exception {
        String key = buildKey(host, port);
        
        // 1. 尝试获取已有连接
        RpcConnection connection = connectionMap.get(key);
        
        if (connection != null && connection.isActive()) {
            log.debug("复用已有连接：{}", key);
            connection.updateLastUseTime();
            return connection;
        }
        
        // 2. 创建新连接
        log.info("创建新连接：{}", key);
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port)).sync();
        
        Channel channel = future.channel();
        RpcConnection newConnection = new RpcConnection(channel, host, port);
        
        connectionMap.put(key, newConnection);
        
        return newConnection;
    }
    
    /**
     * 关闭并移除连接
     */
    public void removeConnection(String host, int port) {
        String key = buildKey(host, port);
        RpcConnection connection = connectionMap.remove(key);
        
        if (connection != null) {
            connection.getChannel().close();
            log.info("连接已关闭：{}", key);
        }
    }
    
    /**
     * 关闭所有连接
     */
    public void closeAll() {
        for (RpcConnection connection : connectionMap.values()) {
            try {
                connection.getChannel().close().sync();
            } catch (Exception e) {
                log.error("关闭连接失败", e);
            }
        }
        connectionMap.clear();
        log.info("所有连接已关闭");
    }
    
    /**
     * 构建连接键
     */
    private String buildKey(String host, int port) {
        return host + ":" + port;
    }
    
    /**
     * 获取连接池大小
     */
    public int size() {
        return connectionMap.size();
    }
}
```

---

## 六、Netty 客户端主类

### 6.1 客户端实现

```java
package com.rpc.client.netty;

import com.rpc.client.connection.RpcConnection;
import com.rpc.client.manager.RequestManager;
import com.rpc.client.netty.handler.RpcClientHandler;
import com.rpc.client.pool.ConnectionPool;
import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.MessageType;
import com.rpc.core.protocol.codec.RpcProtocolDecoder;
import com.rpc.core.protocol.codec.RpcProtocolEncoder;
import com.rpc.core.protocol.impl.RpcRequest;
import com.rpc.core.protocol.impl.RpcResponse;
import com.rpc.core.serialize.SerializerFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RPC Netty 客户端
 */
@Slf4j
public class RpcNettyClient {
    
    private EventLoopGroup eventLoopGroup;
    private ConnectionPool connectionPool;
    private RequestManager requestManager;
    
    private int connectTimeout = 5000;  // 连接超时 5 秒
    private int readTimeout = 10000;     // 读取超时 10 秒
    
    public RpcNettyClient() {
        this.eventLoopGroup = new NioEventLoopGroup();
        this.requestManager = new RequestManager();
        
        // 创建 Bootstrap
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                 .channel(NioSocketChannel.class)
                 .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
                 .option(ChannelOption.TCP_NODELAY, true)
                 .handler(new LoggingHandler(LogLevel.INFO))
                 .handler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     protected void initChannel(SocketChannel ch) {
                         ch.pipeline()
                           .addLast("idleStateHandler", 
                                   new IdleStateHandler(0, 30, 0, TimeUnit.SECONDS))
                           .addLast("decoder", new RpcProtocolDecoder())
                           .addLast("encoder", new RpcProtocolEncoder())
                           .addLast("handler", new RpcClientHandler(requestManager));
                     }
                 });
        
        this.connectionPool = new ConnectionPool(bootstrap);
    }
    
    /**
     * 发送 RPC 请求（同步方式）
     * @param rpcRequest RPC 请求对象
     * @return RPC 响应
     */
    public RpcResponse sendRequest(RpcRequest rpcRequest) {
        return sendRequest(rpcRequest, "127.0.0.1", 8080);
    }
    
    /**
     * 发送 RPC 请求到指定服务器
     */
    public RpcResponse sendRequest(RpcRequest rpcRequest, String host, int port) {
        try {
            // 1. 生成请求 ID
            long requestId = generateRequestId();
            rpcRequest.setRequestId(requestId);
            
            // 2. 创建 Future 用于接收响应
            CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);
            
            // 3. 获取连接
            RpcConnection connection = connectionPool.getConnection(host, port);
            
            // 4. 构建请求消息
            RpcHeader header = RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .serializerType(SerializerFactory.DEFAULT_SERIALIZER.getSerializerType())
                .messageType(MessageType.REQUEST)
                .reserved((byte) 0)
                .requestId(requestId)
                .build();
            
            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(rpcRequest);
            
            // 5. 发送消息
            connection.getChannel().writeAndFlush(message).sync();
            log.debug("请求已发送：{}.{}", rpcRequest.getServiceName(), 
                     rpcRequest.getMethodName());
            
            // 6. 同步等待响应（带超时）
            RpcResponse response = future.get(readTimeout, TimeUnit.MILLISECONDS);
            
            // 7. 检查响应状态
            if (response.getCode() != 200) {
                throw new RuntimeException("RPC 调用失败：" + response.getMessage());
            }
            
            return response;
            
        } catch (Exception e) {
            log.error("发送请求失败", e);
            requestManager.failRequest(rpcRequest.getRequestId(), e);
            throw new RuntimeException("发送请求失败", e);
        }
    }
    
    /**
     * 异步发送请求
     */
    public CompletableFuture<RpcResponse> sendRequestAsync(
            RpcRequest rpcRequest, String host, int port) {
        
        try {
            // 1. 生成请求 ID
            long requestId = generateRequestId();
            rpcRequest.setRequestId(requestId);
            
            // 2. 创建 Future
            CompletableFuture<RpcResponse> future = requestManager.addRequest(requestId);
            
            // 3. 获取连接
            RpcConnection connection = connectionPool.getConnection(host, port);
            
            // 4. 构建消息
            RpcHeader header = RpcHeader.builder()
                .magicNumber(RpcHeader.MAGIC_NUMBER)
                .version(RpcHeader.VERSION)
                .serializerType(SerializerFactory.DEFAULT_SERIALIZER.getSerializerType())
                .messageType(MessageType.REQUEST)
                .reserved((byte) 0)
                .requestId(requestId)
                .build();
            
            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(rpcRequest);
            
            // 5. 异步发送
            connection.getChannel().writeAndFlush(message)
                .addListener(f -> {
                    if (!f.isSuccess()) {
                        requestManager.failRequest(requestId, f.cause());
                    }
                });
            
            return future;
            
        } catch (Exception e) {
            log.error("异步发送请求失败", e);
            CompletableFuture<RpcResponse> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
        }
    }
    
    /**
     * 生成请求 ID
     */
    private long generateRequestId() {
        // 简单实现：使用时间戳
        return System.nanoTime();
    }
    
    /**
     * 关闭客户端
     */
    public void close() {
        log.info("正在关闭客户端...");
        
        if (connectionPool != null) {
            connectionPool.closeAll();
        }
        
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully()
                       .awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
        
        log.info("客户端已关闭");
    }
}
```

---

## 七、整合动态代理

### 7.1 完整的 RPC 代理工厂

```java
package com.rpc.client.proxy;

import com.rpc.client.netty.RpcNettyClient;
import com.rpc.core.protocol.impl.RpcRequest;
import com.rpc.core.protocol.impl.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * RPC 代理工厂（完整版）
 */
@Slf4j
public class RpcProxyFactory {
    
    private static RpcNettyClient client;
    
    /**
     * 初始化客户端
     */
    public static void initClient(RpcNettyClient rpcClient) {
        client = rpcClient;
    }
    
    /**
     * 创建代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> serviceClass) {
        return createProxy(serviceClass, "127.0.0.1", 8080);
    }
    
    /**
     * 创建代理对象（指定服务器地址）
     */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> serviceClass, String host, int port) {
        
        return (T) Proxy.newProxyInstance(
            serviceClass.getClassLoader(),
            new Class<?>[]{serviceClass},
            new RpcInvocationHandler(serviceClass, host, port)
        );
    }
    
    /**
     * RPC 调用处理器
     */
    @Slf4j
    private static class RpcInvocationHandler implements InvocationHandler {
        
        private final Class<?> serviceClass;
        private final String host;
        private final int port;
        
        public RpcInvocationHandler(Class<?> serviceClass, String host, int port) {
            this.serviceClass = serviceClass;
            this.host = host;
            this.port = port;
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // 1. 跳过 Object 类的方法
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }
            
            // 2. 构建 RPC 请求
            RpcRequest request = new RpcRequest();
            request.setServiceName(serviceClass.getName());
            request.setMethodName(method.getName());
            request.setParameterTypes(method.getParameterTypes());
            request.setParameters(args);
            request.setReturnType(method.getReturnType());
            
            log.info("准备调用：{}.{}", request.getServiceName(), 
                    request.getMethodName());
            
            // 3. 发送远程请求
            if (client == null) {
                throw new IllegalStateException("RPC 客户端未初始化");
            }
            
            RpcResponse response = client.sendRequest(request, host, port);
            
            // 4. 返回结果
            if (response.getCode() == 200) {
                return response.getData();
            } else {
                throw new RuntimeException("RPC 调用失败：" + response.getMessage());
            }
        }
    }
}
```

---

## 八、完整使用示例

### 8.1 客户端测试代码

```java
package com.rpc.example.consumer;

import com.rpc.client.netty.RpcNettyClient;
import com.rpc.client.proxy.RpcProxyFactory;
import com.rpc.example.api.HelloService;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 消费者测试
 */
@Slf4j
public class RpcConsumerTest {
    
    public static void main(String[] args) {
        RpcNettyClient client = null;
        
        try {
            // 1. 创建并初始化客户端
            client = new RpcNettyClient();
            RpcProxyFactory.initClient(client);
            
            // 2. 创建代理
            HelloService service = RpcProxyFactory.createProxy(
                HelloService.class, 
                "127.0.0.1", 
                8080
            );
            
            // 3. 调用方法
            log.info("========== 测试 sayHello ==========");
            String result1 = service.sayHello("张三");
            log.info("结果：{}", result1);
            
            log.info("========== 测试 sayHi ==========");
            String result2 = service.sayHi("李四");
            log.info("结果：{}", result2);
            
            log.info("========== 测试 add ==========");
            Integer result3 = service.add(10, 20);
            log.info("结果：{}", result3);
            
            log.info("========== 所有测试完成 ==========");
            
        } catch (Exception e) {
            log.error("测试失败", e);
        } finally {
            // 4. 关闭客户端
            if (client != null) {
                client.close();
            }
        }
    }
}
```

### 8.2 运行步骤

**步骤 1**：先启动服务端（第 6 课的 `RpcProviderBootstrap`）

```
运行：com.rpc.example.provider.RpcProviderBootstrap.main()
输出：RPC 服务器启动成功，监听端口：8080
```

**步骤 2**：再启动客户端

```
运行：com.rpc.example.consumer.RpcConsumerTest.main()
输出：
========== 测试 sayHello ==========
准备调用：com.rpc.example.api.HelloService.sayHello
信息：收到 RPC 请求：com.rpc.example.api.HelloService.sayHello
信息：收到 sayHello 请求：张三
结果：Hello, 张三!
... (其他测试结果)
```

---

## 九、本课总结

### 核心知识点

1. **Future/Promise 模式**
   - 将异步操作转换为同步结果
   - 使用 `CompletableFuture` 实现

2. **请求管理器**
   - 维护请求 ID 与 Future 的映射
   - 收到响应时完成对应的 Future

3. **连接池管理**
   - 每个服务器地址维护一个长连接
   - 自动重连机制

4. **Netty 客户端**
   - Bootstrap 配置
   - 编解码器配置
   - 心跳检测

5. **同步转异步**
   - 用户调用是同步的
   - 底层网络 IO 是异步的
   - 通过 Future 模式桥接

### 课后思考

1. 为什么要用连接池而不是每次创建新连接？
2. 如果服务端宕机，客户端应该如何处理？
3. 如何实现请求超时机制？
4. 如果要支持批量请求，应该如何修改？

---

## 十、动手练习

### 练习 1：实现连接健康检查

定期 ping 服务器，如果连接失效则自动重连。

### 练习 2：实现请求超时

为每个请求添加超时时间，超时后主动取消请求。

提示：
```java
scheduledExecutor.schedule(() -> {
    requestManager.failRequest(requestId, 
        new TimeoutException("请求超时"));
}, timeout, TimeUnit.MILLISECONDS);
```

### 练习 3：实现连接池大小限制

修改连接池，使其支持最大连接数限制。

---

## 十一、下一步

下一节课我们将实现**服务注册与发现**，使用 ZooKeeper 作为注册中心。

**[跳转到第 8 课：服务注册与发现](./lesson-08-service-registry.md)**
